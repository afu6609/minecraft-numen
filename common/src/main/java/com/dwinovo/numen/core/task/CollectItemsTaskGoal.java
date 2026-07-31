package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.TargetSet;
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
 * scattered drop with the pathfinder so nothing is left behind after a mine or a
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

    private static final double WALK_SPEED = 1.0;
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
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) {
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
        nav = new PlayerNav(player, this::targetCell, WALK_SPEED, this::picked);
        phase = Phase.APPROACH;
        return TaskState.RUNNING;
    }

    private TaskState tickApproach() {
        pollPickupReceipt();
        if (target == null || target.isRemoved()) {
            if (targetReceived == 0) {
                disappearedWithoutReceipt++;
            }
            finishTarget();
            return TaskState.RUNNING;
        }
        if (!PlayerInv.canAcceptMain(
                player.getInventory(), target.getItem())) {
            blockedItem = BuiltInRegistries.ITEM
                    .getKey(target.getItem().getItem())
                    .toString();
            stopNav();
            fail(inventoryFullMessage(), FailureType.NO_MATERIAL);
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
                stopNav();
                clearTarget();
                phase = Phase.SCAN;
            }
        }
        return TaskState.RUNNING;
    }

    private TaskState tickConfirm() {
        pollPickupReceipt();
        if (target == null || target.isRemoved()) {
            if (targetReceived == 0) {
                disappearedWithoutReceipt++;
            }
            finishTarget();
            return TaskState.RUNNING;
        }
        if (!PlayerInv.canAcceptMain(
                player.getInventory(), target.getItem())) {
            blockedItem = itemName(target.getItem().getItem());
            fail(inventoryFullMessage(), FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }
        if (player.distanceToSqr(target) > PICKUP_REACH_SQR) {
            confirmReadyTicks = 0;
            nav = new PlayerNav(
                    player, this::targetCell, WALK_SPEED, this::picked);
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
            clearTarget();
            phase = Phase.SCAN;
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

    private void finishTarget() {
        stopNav();
        clearTarget();
        phase = Phase.SCAN;
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
}
