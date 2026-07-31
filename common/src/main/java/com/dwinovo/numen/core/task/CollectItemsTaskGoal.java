package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.TargetSet;
import com.dwinovo.numen.task.control.BodyControlClass;
import com.dwinovo.numen.task.control.BodyControlPolicies;
import com.dwinovo.numen.task.navigation.BodyNavigationPort;
import com.dwinovo.numen.task.navigation.BodyNavigationPorts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Intent-level item sweeper for {@link CollectItemsTaskRecord}: "pick up the
 * dropped items around here." The entity already auto-absorbs items within ~1
 * block ({@code setCanPickUpLoot}); this goal actively walks it to each
 * scattered drop through the optional body-navigation provider (or the legacy
 * pathfinder when unsupported) so nothing is left behind after a mine or a
 * melee_attack.
 *
 * <h2>State machine (per tick)</h2>
 * <pre>
 *   SCAN     → nearest matching ItemEntity within the radius; none → DONE.
 *   APPROACH → navigate to the drop; arrival only means the body got there.
 *   CONFIRM  → wait for the vanilla ITEM_PICKED_UP statistic to acknowledge
 *              the actual inventory transfer, then re-SCAN.
 * </pre>
 */
public final class CollectItemsTaskGoal extends AbstractCompanionTask<CollectItemsTaskRecord> {

    private enum Phase { SCAN, APPROACH, CONFIRM }

    /**
     * Backend choice is sticky for the whole public collect task. Once an
     * external provider accepts one target, no later target may silently fall
     * through to PlayerNav.
     */
    private enum NavigationBackend {
        UNDECIDED,
        PORT,
        LEGACY
    }

    /** Work to resume only after an accepted provider operation detaches. */
    private enum PendingDetachAction {
        NONE,
        FINISH_TARGET,
        PORT_ARRIVAL,
        PORT_FAILURE,
        RESTART_APPROACH,
        TERMINAL
    }

    private static final double WALK_SPEED = 1.0;
    private static final double PORT_ARRIVAL_RADIUS = 1.0;
    private static final String LLM_CONTROL_ACTOR = "numen-chain:llm";
    /** Conservative task-wide terrain budget; it is not model configurable. */
    private static final int PORT_TASK_MAX_BROKEN_BLOCKS = 2;
    private static final int PORT_TASK_MAX_BREAK_TICKS = 400;
    private static final int PORT_TASK_MAX_SCAFFOLD_BLOCKS = 4;
    private static final int MAX_PORT_DETACH_RETRY_TICKS = 20;
    private static final BodyNavigationPort.TerrainUsage
            PORT_EXHAUSTED_TERRAIN_USAGE =
            new BodyNavigationPort.TerrainUsage(
                    PORT_TASK_MAX_BROKEN_BLOCKS,
                    PORT_TASK_MAX_BREAK_TICKS,
                    PORT_TASK_MAX_SCAFFOLD_BLOCKS);
    /** If a moving drop drifts farther away during confirmation, route to it again. */
    private static final double PICKUP_REACH_SQR = 1.5;
    /** Once a drop is pickup-ready, ten close ticks without a receipt is a real rejection. */
    private static final int PICKUP_READY_CONFIRM_TICKS = 10;
    /** Hard bound for never-pickup entities and unusually long ownership delays. */
    private static final int PICKUP_TOTAL_CONFIRM_TICKS = 80;

    private Phase phase = Phase.SCAN;
    private ItemEntity target;
    private Item receiptItem;
    private int pickupStatSeen;
    private int inventoryCountSeen;
    private int targetReceived;
    private int confirmReadyTicks;
    private int confirmTotalTicks;
    private boolean blockedByInventory;
    private String blockedItem = "the requested drop";
    private boolean sawMatchingDrop;
    private int unreachableTargets;
    private int rejectedPickups;
    private int disappearedWithoutReceipt;
    private String unresolvedReason = "a matching drop could not be collected";
    private FailureType unresolvedType = FailureType.UNKNOWN;
    private NavigationBackend backend = NavigationBackend.UNDECIDED;
    private BodyNavigationPort.Operation portOperation;
    private int portBrokenBlocks;
    private int portBreakTicks;
    private int portScaffoldBlocks;
    private boolean portTerrainReceiptFailed;
    private PendingDetachAction pendingDetachAction =
            PendingDetachAction.NONE;
    private TaskState pendingDetachTerminalState;
    private String pendingDetachMessage = "";
    private FailureType pendingDetachFailure = FailureType.UNKNOWN;
    private int portDetachRetryTicks;

    /** Item-entity ids we reached but couldn't absorb, so SCAN won't loop on them. */
    private final TargetSet<ItemEntity> skipped = new TargetSet<>(ItemEntity::getId);

    public CollectItemsTaskGoal(NumenPlayer player, CollectItemsTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        this.phase = Phase.SCAN;
        this.target = null;
        this.receiptItem = null;
        this.backend = NavigationBackend.UNDECIDED;
        this.portOperation = null;
        this.portBrokenBlocks = 0;
        this.portBreakTicks = 0;
        this.portScaffoldBlocks = 0;
        this.portTerrainReceiptFailed = false;
        clearPendingDetach();
    }

    @Override
    protected TaskState onTick() {
        if (pendingDetachAction != PendingDetachAction.NONE) {
            return tickPendingDetach();
        }
        if (player.isDeadOrDying()) {
            if (!stopApproach("collect_items body died")) {
                return deferTerminalDetach(
                        TaskState.CANCELLED,
                        "collect_items body died before delegated navigation "
                                + "detached",
                        FailureType.INTERRUPTED);
            }
            return TaskState.CANCELLED;
        }
        return switch (phase) {
            case SCAN -> tickScan();
            case APPROACH -> tickApproach();
            case CONFIRM -> tickConfirm();
        };
    }

    private TaskState tickScan() {
        blockedByInventory = false;
        ItemEntity best = nearestItem();
        if (best == null) {
            if (blockedByInventory) {
                fail(
                        inventoryFullMessage(),
                        FailureType.NO_MATERIAL);
                return TaskState.FAILED;
            }
            if (unreachableTargets > 0 || rejectedPickups > 0) {
                fail(
                        unresolvedReason + "; actually picked up "
                                + r.getCollected() + " item(s), with "
                                + unreachableTargets + " unreachable and "
                                + rejectedPickups + " unconfirmed target(s)",
                        unresolvedType);
                return TaskState.FAILED;
            }
            if (sawMatchingDrop
                    && r.getCollected() == 0
                    && disappearedWithoutReceipt > 0) {
                fail(
                        "matching drops disappeared without a pickup receipt for "
                                + player.getGameProfile().getName()
                                + "; nothing was counted as collected",
                        FailureType.TARGET_LOST);
                return TaskState.FAILED;
            }
            // Nothing left within radius — done. Success even at 0 (the LLM asked
            // us to sweep; "nothing here" is a valid, useful answer).
            return TaskState.SUCCESS;
        }
        beginTarget(best);
        if (!startTargetApproach()) {
            return pendingDetachAction == PendingDetachAction.NONE
                    ? TaskState.FAILED
                    : TaskState.RUNNING;
        }
        phase = Phase.APPROACH;
        return TaskState.RUNNING;
    }

    private TaskState tickApproach() {
        pollPickupReceipt();
        if (target == null || target.isRemoved()) {
            if (targetReceived == 0) {
                disappearedWithoutReceipt++;
            }
            if (!finishTarget("item target disappeared")) {
                return deferDetach(
                        PendingDetachAction.FINISH_TARGET,
                        "item target disappeared",
                        FailureType.UNKNOWN);
            }
            return TaskState.RUNNING;
        }
        if (!PlayerInv.canAcceptMain(
                player.getInventory(), target.getItem())) {
            blockedItem = BuiltInRegistries.ITEM
                    .getKey(target.getItem().getItem())
                    .toString();
            if (!stopApproach("inventory became full")) {
                return deferTerminalDetach(
                        TaskState.FAILED,
                        inventoryFullMessage(),
                        FailureType.NO_MATERIAL);
            }
            fail(inventoryFullMessage(), FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }
        return backend == NavigationBackend.PORT
                ? tickPortApproach()
                : tickLegacyApproach();
    }

    private TaskState tickLegacyApproach() {
        if (nav == null) {
            fail(
                    "legacy item navigation disappeared",
                    FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        switch (nav.tick()) {
            case RUNNING -> { /* walking to it */ }
            case ARRIVED -> {
                // Navigation arrival is not a pickup receipt. Stop driving the
                // body and give vanilla collision/ownership delay time to transfer.
                stopNav();
                phase = Phase.CONFIRM;
            }
            case FAILED -> {
                unreachableTargets++;
                unresolvedReason = "could not reach "
                        + itemName(target.getItem().getItem())
                        + ": " + nav.failReason();
                unresolvedType = nav.failType();
                skipped.skip(target);
                if (!finishTarget("legacy item approach failed")) {
                    return TaskState.FAILED;
                }
            }
        }
        return TaskState.RUNNING;
    }

    private TaskState tickPortApproach() {
        if (portOperation == null) {
            fail(
                    "delegated item approach operation disappeared",
                    FailureType.UNKNOWN);
            return TaskState.FAILED;
        }

        BodyNavigationPort.Snapshot snapshot;
        try {
            snapshot = portOperation.snapshot();
            if (snapshot == null) {
                throw new IllegalStateException(
                        "navigation provider returned no snapshot");
            }
        } catch (RuntimeException failure) {
            return failPortApproach(
                    "navigation provider status failed: "
                            + safeMessage(failure),
                    FailureType.UNKNOWN);
        }

        return switch (snapshot.state()) {
            case PLANNING, MOVING -> TaskState.RUNNING;
            case ARRIVED -> {
                if (!cancelPortOperation(
                        "item pickup radius reported reached")) {
                    yield deferDetach(
                            PendingDetachAction.PORT_ARRIVAL,
                            "item pickup radius reported reached",
                            FailureType.UNKNOWN);
                }
                yield completePortArrival();
            }
            case FAILED -> failPortApproach(
                    snapshotMessage(
                            snapshot,
                            "delegated item approach failed"),
                    mapFailure(snapshot.failure()));
            case CANCELLED -> {
                if (!cancelPortOperation(
                        "provider cancelled delegated item approach")) {
                    yield deferTerminalDetach(
                            TaskState.CANCELLED,
                            "the provider cancelled delegated item approach",
                            FailureType.INTERRUPTED);
                }
                yield TaskState.CANCELLED;
            }
            case HOLDING -> failPortApproach(
                    "item approach provider returned the follow-only "
                            + "HOLDING state",
                    FailureType.UNKNOWN);
        };
    }

    private TaskState failPortApproach(
            String reason,
            FailureType failureType) {
        if (!cancelPortOperation("delegated item approach failed")) {
            return deferDetach(
                    PendingDetachAction.PORT_FAILURE,
                    reason,
                    failureType);
        }
        return completePortFailure(reason, failureType);
    }

    private TaskState completePortFailure(
            String reason,
            FailureType failureType) {
        unreachableTargets++;
        unresolvedReason = "could not reach "
                + (target == null
                        ? "the selected item"
                        : itemName(target.getItem().getItem()))
                + ": " + reason;
        unresolvedType = failureType;
        if (target != null && !target.isRemoved()) {
            skipped.skip(target);
        }
        clearTarget();
        phase = Phase.SCAN;
        return TaskState.RUNNING;
    }

    private TaskState completePortArrival() {
        pollPickupReceipt();
        if (target == null || target.isRemoved()) {
            if (targetReceived == 0) {
                disappearedWithoutReceipt++;
            }
            clearTarget();
            phase = Phase.SCAN;
            return TaskState.RUNNING;
        }

        // Provider arrival is advisory. Numen retains the live-distance
        // verdict and the subsequent vanilla pickup receipt.
        if (player.distanceToSqr(target) > PICKUP_REACH_SQR) {
            unreachableTargets++;
            unresolvedReason =
                    "the navigation provider reported item arrival outside "
                            + "the verified pickup radius";
            unresolvedType = FailureType.STANCE_DUD;
            skipped.skip(target);
            clearTarget();
            phase = Phase.SCAN;
            return TaskState.RUNNING;
        }
        phase = Phase.CONFIRM;
        return TaskState.RUNNING;
    }

    private TaskState tickConfirm() {
        pollPickupReceipt();
        if (target == null || target.isRemoved()) {
            if (targetReceived == 0) {
                disappearedWithoutReceipt++;
            }
            if (!finishTarget(
                    "item pickup confirmed or target disappeared")) {
                return deferDetach(
                        PendingDetachAction.FINISH_TARGET,
                        "item pickup confirmed or target disappeared",
                        FailureType.UNKNOWN);
            }
            return TaskState.RUNNING;
        }
        if (!PlayerInv.canAcceptMain(
                player.getInventory(), target.getItem())) {
            blockedItem = itemName(target.getItem().getItem());
            if (!stopApproach("inventory became full during pickup confirmation")) {
                return deferTerminalDetach(
                        TaskState.FAILED,
                        inventoryFullMessage(),
                        FailureType.NO_MATERIAL);
            }
            fail(inventoryFullMessage(), FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }
        if (player.distanceToSqr(target) > PICKUP_REACH_SQR) {
            confirmReadyTicks = 0;
            if (!startTargetApproach()) {
                return pendingDetachAction == PendingDetachAction.NONE
                        ? TaskState.FAILED
                        : TaskState.RUNNING;
            }
            phase = Phase.APPROACH;
            return TaskState.RUNNING;
        }

        confirmTotalTicks++;
        if (target.hasPickUpDelay()) {
            confirmReadyTicks = 0;
        } else {
            confirmReadyTicks++;
        }
        if (confirmReadyTicks >= PICKUP_READY_CONFIRM_TICKS
                || confirmTotalTicks >= PICKUP_TOTAL_CONFIRM_TICKS) {
            rejectedPickups++;
            unresolvedReason = "reached " + itemName(target.getItem().getItem())
                    + " but vanilla reported no pickup for "
                    + confirmTotalTicks + " tick(s)";
            unresolvedType = FailureType.UNKNOWN;
            skipped.skip(target);
            if (!finishTarget("pickup confirmation timed out")) {
                return deferDetach(
                        PendingDetachAction.FINISH_TARGET,
                        "pickup confirmation timed out",
                        FailureType.UNKNOWN);
            }
        }
        return TaskState.RUNNING;
    }

    private BlockPos targetCell() {
        return (target != null && !target.isRemoved()) ? target.blockPosition() : null;
    }

    /** Only entity removal can short-circuit navigation; proximity is not receipt. */
    private boolean picked() {
        return target == null || target.isRemoved();
    }

    /**
     * Start one target approach without ever allowing two motor backends to
     * coexist. The first admission fixes the backend for the whole collect
     * task; only the initial UNSUPPORTED result may create PlayerNav.
     */
    private boolean startTargetApproach() {
        if (target == null || target.isRemoved()) {
            fail(
                    "cannot approach an item target that is no longer present",
                    FailureType.TARGET_LOST);
            return false;
        }
        if (!cancelPortOperation("starting the next item approach")) {
            deferDetach(
                    PendingDetachAction.RESTART_APPROACH,
                    "starting the next item approach",
                    FailureType.UNKNOWN);
            return false;
        }
        stopNav();

        if (backend == NavigationBackend.LEGACY) {
            startLegacyApproach();
            return true;
        }

        BodyNavigationPort.StartResult started = startPortApproach();
        return switch (started.disposition()) {
            case ACCEPTED -> {
                backend = NavigationBackend.PORT;
                portOperation = started.operation();
                portTerrainReceiptFailed = false;
                yield true;
            }
            case UNSUPPORTED -> {
                if (backend == NavigationBackend.PORT) {
                    fail(
                            "delegated item approach became unsupported after "
                                    + "this collect task had committed to it: "
                                    + startMessage(started),
                            FailureType.UNSUPPORTED);
                    yield false;
                }
                backend = NavigationBackend.LEGACY;
                startLegacyApproach();
                yield true;
            }
            case REJECTED -> {
                // A rejection may follow provider-side admission work, so it
                // is never a safe signal to start the legacy navigator.
                backend = NavigationBackend.PORT;
                fail(
                        "could not start delegated item approach: "
                                + startMessage(started),
                        FailureType.UNKNOWN);
                yield false;
            }
        };
    }

    private void startLegacyApproach() {
        nav = new PlayerNav(
                player,
                this::targetCell,
                WALK_SPEED,
                this::picked);
    }

    /**
     * Borrow the exact already-acquired LLM task session. Native navigation is
     * a motor beneath task {@link CollectItemsTaskRecord#publicId()}, never a
     * second body-control actor or player-visible task.
     */
    private BodyNavigationPort.StartResult startPortApproach() {
        var control = BodyControlPolicies.requestFor(
                player,
                LLM_CONTROL_ACTOR,
                r.publicId(),
                BodyControlClass.DIRECTED_ACTION,
                BodyControlClass.DIRECTED_ACTION.defaultPriority());
        if (!BodyControlPolicies.owns(control)) {
            return BodyNavigationPort.StartResult.rejected(
                    "the exact Numen collect task session does not own body "
                            + "control");
        }

        try {
            return BodyNavigationPorts.startApproachItem(
                    new BodyNavigationPort.ApproachItemRequest(
                            new BodyNavigationPort.ControlBinding(control),
                            target.getUUID(),
                            PORT_ARRIVAL_RADIUS,
                            remainingPortTerrainAllowance()));
        } catch (RuntimeException failure) {
            return BodyNavigationPort.StartResult.rejected(
                    "navigation provider start failed: "
                            + safeMessage(failure));
        }
    }

    private boolean stopApproach(String reason) {
        stopNav();
        return cancelPortOperation(reason);
    }

    /**
     * Cancel synchronously before changing target or finalizing. The public
     * facade deliberately permits retry when a provider throws, so make one
     * immediate retry and retain the handle if both attempts fail.
     */
    private boolean cancelPortOperation(String reason) {
        BodyNavigationPort.Operation operation = portOperation;
        if (operation == null) return true;
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            BodyNavigationPort.TerrainUsage usage;
            try {
                usage = operation.terrainUsage();
                if (usage == null) {
                    throw new IllegalStateException(
                            "navigation provider returned no terrain usage");
                }
            } catch (RuntimeException failure) {
                portTerrainReceiptFailed = true;
                usage = PORT_EXHAUSTED_TERRAIN_USAGE;
                com.dwinovo.numen.Constants.LOG.warn(
                        "[numen-task] delegated collect navigation terrain "
                                + "receipt failed; exhausting task budget: {}",
                        safeMessage(failure));
            }
            try {
                operation.cancel(reason);
                absorbPortTerrainUsage(
                        portTerrainReceiptFailed
                                ? PORT_EXHAUSTED_TERRAIN_USAGE
                                : usage);
                portOperation = null;
                portTerrainReceiptFailed = false;
                return true;
            } catch (RuntimeException failure) {
                lastFailure = failure;
                com.dwinovo.numen.Constants.LOG.warn(
                        "[numen-task] delegated item approach cancel attempt "
                                + "{} failed: {}",
                        attempt,
                        safeMessage(failure));
            }
        }
        if (lastFailure != null) {
            com.dwinovo.numen.Constants.LOG.error(
                    "[numen-task] delegated item approach remains attached "
                            + "after cancellation retries: {}",
                    safeMessage(lastFailure));
        }
        return false;
    }

    private BodyNavigationPort.TerrainAllowance
            remainingPortTerrainAllowance() {
        int remainingBlocks = Math.max(
                0,
                PORT_TASK_MAX_BROKEN_BLOCKS - portBrokenBlocks);
        int remainingBreakTicks = Math.max(
                0,
                PORT_TASK_MAX_BREAK_TICKS - portBreakTicks);
        if (remainingBlocks == 0 || remainingBreakTicks == 0) {
            remainingBlocks = 0;
            remainingBreakTicks = 0;
        }
        int remainingScaffolds = Math.max(
                0,
                PORT_TASK_MAX_SCAFFOLD_BLOCKS - portScaffoldBlocks);
        return new BodyNavigationPort.TerrainAllowance(
                remainingBlocks,
                remainingBreakTicks,
                remainingScaffolds,
                false,
                false);
    }

    private void absorbPortTerrainUsage(
            BodyNavigationPort.TerrainUsage usage) {
        portBrokenBlocks = saturatingAdd(
                portBrokenBlocks,
                usage.brokenBlocks(),
                PORT_TASK_MAX_BROKEN_BLOCKS);
        portBreakTicks = saturatingAdd(
                portBreakTicks,
                usage.breakTicks(),
                PORT_TASK_MAX_BREAK_TICKS);
        portScaffoldBlocks = saturatingAdd(
                portScaffoldBlocks,
                usage.scaffoldBlocks(),
                PORT_TASK_MAX_SCAFFOLD_BLOCKS);
    }

    private static int saturatingAdd(
            int current,
            int increment,
            int ceiling) {
        return (int) Math.min(ceiling, (long) current + increment);
    }

    private TaskState deferDetach(
            PendingDetachAction action,
            String message,
            FailureType failureType) {
        if (pendingDetachAction == PendingDetachAction.NONE) {
            pendingDetachAction = action;
            pendingDetachMessage = message == null ? "" : message;
            pendingDetachFailure = failureType == null
                    ? FailureType.UNKNOWN
                    : failureType;
            portDetachRetryTicks = 0;
        }
        return TaskState.RUNNING;
    }

    private TaskState deferTerminalDetach(
            TaskState state,
            String message,
            FailureType failureType) {
        if (pendingDetachAction == PendingDetachAction.NONE) {
            pendingDetachTerminalState = state;
        }
        return deferDetach(
                PendingDetachAction.TERMINAL,
                message,
                failureType);
    }

    private TaskState tickPendingDetach() {
        if (!cancelPortOperation(
                "retrying delegated collect navigation detach")) {
            if (++portDetachRetryTicks < MAX_PORT_DETACH_RETRY_TICKS) {
                return TaskState.RUNNING;
            }
            String context = pendingDetachMessage;
            clearPendingDetach();
            fail(
                    "delegated collect navigation did not detach within "
                            + MAX_PORT_DETACH_RETRY_TICKS
                            + " ticks; the enclosing control lease will fence "
                            + "further body writes"
                            + (context.isBlank() ? "" : ": " + context),
                    FailureType.INTERRUPTED);
            return TaskState.FAILED;
        }

        PendingDetachAction action = pendingDetachAction;
        TaskState terminalState = pendingDetachTerminalState;
        String message = pendingDetachMessage;
        FailureType failureType = pendingDetachFailure;
        clearPendingDetach();
        return switch (action) {
            case FINISH_TARGET -> {
                pollPickupReceipt();
                clearTarget();
                phase = Phase.SCAN;
                yield TaskState.RUNNING;
            }
            case PORT_ARRIVAL -> completePortArrival();
            case PORT_FAILURE -> completePortFailure(
                    message,
                    failureType);
            case RESTART_APPROACH -> {
                pollPickupReceipt();
                if (target == null || target.isRemoved()) {
                    if (targetReceived == 0) {
                        disappearedWithoutReceipt++;
                    }
                    clearTarget();
                    phase = Phase.SCAN;
                    yield TaskState.RUNNING;
                }
                if (!startTargetApproach()) {
                    yield pendingDetachAction == PendingDetachAction.NONE
                            ? TaskState.FAILED
                            : TaskState.RUNNING;
                }
                phase = Phase.APPROACH;
                yield TaskState.RUNNING;
            }
            case TERMINAL -> {
                if (terminalState == TaskState.FAILED) {
                    fail(message, failureType);
                }
                yield terminalState == null
                        ? TaskState.FAILED
                        : terminalState;
            }
            case NONE -> TaskState.RUNNING;
        };
    }

    private void clearPendingDetach() {
        pendingDetachAction = PendingDetachAction.NONE;
        pendingDetachTerminalState = null;
        pendingDetachMessage = "";
        pendingDetachFailure = FailureType.UNKNOWN;
        portDetachRetryTicks = 0;
    }

    private static String startMessage(
            BodyNavigationPort.StartResult result) {
        if (result == null) return "provider returned no result";
        return result.message() == null || result.message().isBlank()
                ? result.disposition().name().toLowerCase()
                : result.message();
    }

    private static String snapshotMessage(
            BodyNavigationPort.Snapshot snapshot,
            String fallback) {
        return snapshot.message() == null || snapshot.message().isBlank()
                ? fallback
                : snapshot.message();
    }

    private static FailureType mapFailure(
            BodyNavigationPort.FailureKind failure) {
        return switch (failure) {
            case NO_PATH -> FailureType.NO_PATH;
            case BOXED_IN -> FailureType.BOXED_IN;
            case TARGET_LOST -> FailureType.TARGET_LOST;
            case OUT_OF_RANGE -> FailureType.OUT_OF_REACH;
            case CONTROL_LOST -> FailureType.INTERRUPTED;
            case NO_MATERIAL -> FailureType.NO_MATERIAL;
            case HAZARD -> FailureType.HAZARD;
            case CAPABILITY_MISMATCH -> FailureType.UNSUPPORTED;
            case NONE, INTERNAL -> FailureType.UNKNOWN;
        };
    }

    private static String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    private ItemEntity nearestItem() {
        AABB box = player.getBoundingBox().inflate(r.radius);
        List<ItemEntity> candidates = new ArrayList<>();
        for (Entity e : player.level().getEntities(player, box)) {
            if (!(e instanceof ItemEntity ie) || ie.isRemoved()) continue;
            if (!r.filter.isEmpty() && !r.filter.contains(ie.getItem().getItem())) continue;
            sawMatchingDrop = true;
            if (!PlayerInv.canAcceptMain(
                    player.getInventory(), ie.getItem())) {
                blockedByInventory = true;
                blockedItem = BuiltInRegistries.ITEM
                        .getKey(ie.getItem().getItem())
                        .toString();
                continue;
            }
            candidates.add(ie);
        }
        return skipped.pick(candidates, Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }

    private void beginTarget(ItemEntity selected) {
        target = selected;
        receiptItem = selected.getItem().getItem();
        pickupStatSeen = pickupStat(receiptItem);
        inventoryCountSeen = PlayerInv.mainCount(
                player.getInventory(), receiptItem);
        targetReceived = 0;
        confirmReadyTicks = 0;
        confirmTotalTicks = 0;
    }

    /**
     * Count units transferred into this player. The vanilla pickup statistic is
     * authoritative for complete pickup; the same-item main-inventory delta also
     * catches partial insertion, for which Inventory.add mutates the stack but
     * returns false and vanilla does not award ITEM_PICKED_UP.
     */
    private int pollPickupReceipt() {
        if (receiptItem == null) return 0;
        int nowStat = pickupStat(receiptItem);
        int nowInventory = PlayerInv.mainCount(
                player.getInventory(), receiptItem);
        int received = receivedUnits(
                pickupStatSeen, nowStat,
                inventoryCountSeen, nowInventory);
        pickupStatSeen = nowStat;
        inventoryCountSeen = nowInventory;
        if (received > 0) {
            r.addCollected(received);
            targetReceived += received;
        }
        return received;
    }

    static int receivedUnits(
            int statBefore,
            int statNow,
            int inventoryBefore,
            int inventoryNow) {
        int statDelta = Math.max(0, statNow - statBefore);
        int inventoryDelta = Math.max(0, inventoryNow - inventoryBefore);
        return Math.max(statDelta, inventoryDelta);
    }

    private int pickupStat(Item item) {
        return player.getStats().getValue(Stats.ITEM_PICKED_UP, item);
    }

    private boolean finishTarget(String reason) {
        if (!stopApproach(reason)) {
            return false;
        }
        clearTarget();
        phase = Phase.SCAN;
        return true;
    }

    private void clearTarget() {
        target = null;
        receiptItem = null;
        targetReceived = 0;
        confirmReadyTicks = 0;
        confirmTotalTicks = 0;
    }

    private static String itemName(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private String inventoryFullMessage() {
        return "main inventory is full and cannot accept " + blockedItem
                + "; actually picked up " + r.getCollected() + " item(s). "
                + "Do not discard anything automatically: pause and ask the owner "
                + "whether to store items in a chest (interact_at, inspect_gui, transfer) "
                + "or use drop_items, then retry collect_items.";
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("label", r.label);
        data.put("collected", r.getCollected());
        data.put("radius", r.radius);
        data.put(
                "main_slots_used",
                PlayerInv.mainSlotsUsed(player.getInventory()));
        data.put(
                "main_slots_total",
                player.getInventory().items.size());
        data.put(
                "inventory_no_empty_slots",
                PlayerInv.mainSlotsFree(player.getInventory()) == 0);
        data.put("unreachable_targets", unreachableTargets);
        data.put("unconfirmed_targets", rejectedPickups);
        data.put("disappeared_without_receipt", disappearedWithoutReceipt);
        data.put("navigation_backend", backend.name().toLowerCase());
        data.put("route_broken_blocks", portBrokenBlocks);
        data.put("route_break_ticks", portBreakTicks);
        data.put("route_scaffold_blocks", portScaffoldBlocks);
        return data;
    }

    @Override
    protected String successMessage() {
        if (!sawMatchingDrop) {
            return "no matching dropped items found within " + r.radius
                    + " blocks";
        }
        return "actually picked up " + r.getCollected() + " " + r.label;
    }

    @Override
    protected String timeoutMessage() {
        return "timed out after collecting " + r.getCollected() + " " + r.label;
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted after collecting " + r.getCollected() + " " + r.label;
    }

    @Override
    protected void cleanup() {
        cancelPortOperation("collect_items task finalized");
        super.cleanup();
    }
}
