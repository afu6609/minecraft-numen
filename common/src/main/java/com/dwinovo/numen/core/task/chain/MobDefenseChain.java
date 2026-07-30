package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.combat.observe.CombatAction;
import com.dwinovo.numen.core.combat.observe.CombatObservation;
import com.dwinovo.numen.core.combat.observe.CombatObservationService;
import com.dwinovo.numen.core.combat.policy.CombatPolicyAction;
import com.dwinovo.numen.core.combat.policy.CombatPolicyCursor;
import com.dwinovo.numen.core.combat.policy.CombatPolicyRuntime;
import com.dwinovo.numen.core.combat.policy.CombatPolicySnapshot;
import com.dwinovo.numen.core.event.CompanionEventBus;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.MeleeAttackTaskRecord;
import com.dwinovo.numen.core.task.RangedAttackTaskRecord;
import com.dwinovo.numen.core.task.SurvivalConfig;
import com.dwinovo.numen.core.task.base.ToolSelect;
import com.dwinovo.numen.core.task.combat.CombatDecision;
import com.dwinovo.numen.core.task.combat.CombatDecision.Action;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.EngagementDirective;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.ThreatType;
import com.dwinovo.numen.core.task.combat.CombatDecisionInputFactory;
import com.dwinovo.numen.core.task.combat.CombatEquipment;
import com.dwinovo.numen.core.task.combat.CombatRangedExecutor;
import com.dwinovo.numen.core.task.combat.CombatShelterResolver;
import com.dwinovo.numen.core.task.combat.CombatShelterResolver.LiveShelter;
import com.dwinovo.numen.core.task.combat.CombatTerrainProbe;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import com.dwinovo.numen.core.task.survival.ThreatMemory;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskChain;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Low-latency survival director. The model may author bounded tactics, but this
 * chain owns immediate observation, non-bypassable fight/flight vetoes, shelter
 * routing and tick-sensitive execution.
 */
public final class MobDefenseChain implements TaskChain, com.dwinovo.numen.task.reflex.Reflex {
    /** Idle discovery is event-assisted; a damage event wakes ThreatMemory immediately. */
    private static final int IDLE_SCAN_INTERVAL_TICKS = 10;

    private static final double SCAN_RADIUS = 32.0;
    private static final double RECENT_ATTACKER_RADIUS = 40.0;
    private static final double EXPLICIT_TARGET_RADIUS = 64.0;
    private static final double ATTACK_REACH = 3.0;
    private static final double ATTACK_REACH_SQR = ATTACK_REACH * ATTACK_REACH;
    private static final double CHASE_SPEED = 1.2;
    private static final double SAFETY_SPEED = 1.3;
    private static final int MIN_EPISODE_LOCK_TICKS = 40;
    private static final int QUIET_CONFIRM_TICKS = 100;
    private static final int TERRAIN_REFRESH_TICKS = 10;
    private static final int SHELTER_REFRESH_TICKS = 20;
    private static final int SAFETY_COMMIT_TICKS = 40;
    private static final int CREEPER_KITE_TICKS = 24;
    private static final int FAILED_RANGED_HOLD_TICKS = 40;
    private static final int SHELTER_FAILURE_COOLDOWN_TICKS = 200;
    private static final int MAX_DOOR_FAILURES = 3;
    private static final int MAX_POLICY_EPISODE_TICKS = 160;
    private static final int MAX_EPISODE_ENTITIES = 64;
    private static final int MAX_NAV_FAILURES = 2;
    private static final double SAFE_MEAL_DISTANCE = 12.0;
    private static final long QUIET_BEFORE_MEAL_TICKS = 30;

    private enum Mode {
        NONE,
        MELEE,
        RANGED,
        RETREAT,
        COVER,
        SHELTER_APPROACH,
        SHELTER_CROSS,
        SECURE_DOOR,
        SHELTER_SETTLE,
        HOLD
    }

    private record ThreatContext(
            List<LivingEntity> threats,
            Map<Integer, CombatObservation> observations,
            EngagementDirective directive) {}

    private record PolicyProposal(
            CombatPolicyAction kind,
            Action supervisorAction) {}

    private final com.dwinovo.numen.task.BodyLog bodyLog;
    private final CombatRangedExecutor ranged = new CombatRangedExecutor();

    private Mode mode = Mode.NONE;
    private PlayerNav nav;
    private int navTargetId = -1;
    private Interaction melee;
    private int meleeTargetId = -1;
    private Interaction guard;
    private Interaction doorInteraction;
    private Boolean doorInteractionGoal;
    private int consecutiveNavFails;
    private boolean blindRetreat;
    private long safetyCommitUntil;
    private Action committedSafety;
    private long creeperKiteUntil;
    private long rangedBlockedUntil;
    private long shelterBlockedUntil;
    private int doorFailures;
    private BlockPos doorAttemptPos;
    private Boolean doorAttemptGoal;
    private boolean pendingDoorClose;
    private BlockPos lastThreatPos;
    private LivingEntity lastFocus;

    private long contextTick = Long.MIN_VALUE;
    private long nextIdleScanTick = Long.MIN_VALUE;
    private ThreatContext cachedContext;
    private long terrainTick = Long.MIN_VALUE;
    private CombatTerrainProbe.Result terrain;
    private long shelterTick = Long.MIN_VALUE;
    private LiveShelter shelter;

    private boolean episodeActive;
    private Vec3 episodeStartPosition;
    private float episodeStartHealth;
    private long episodeStartGameTime;
    private long lastThreatSeenGameTime;
    private String episodeThreatType;
    private int maxEngagedThreats;
    private final Map<Integer, LivingEntity> episodeEntities = new LinkedHashMap<>();
    private CombatDecision lastDecision;
    private int meleeHits;
    private int rangedShots;
    private boolean bodyAnnounced;

    private CombatPolicySnapshot policySnapshot;
    private CombatPolicyCursor policyCursor;
    private UUID policyTargetUuid;
    private CombatPolicyAction currentPolicyAction;
    private String episodePolicyId;
    private int episodePolicyTicks;
    private boolean episodePolicyFailed;
    private boolean episodePolicySafetyPreempted;

    public MobDefenseChain() {
        this(null);
    }

    public MobDefenseChain(com.dwinovo.numen.task.BodyLog bodyLog) {
        this.bodyLog = bodyLog;
    }

    @Override
    public float getPriority(NumenPlayer companion) {
        announceBody(companion);
        if (!SurvivalConfig.enabled()) return Float.NEGATIVE_INFINITY;
        if (!com.dwinovo.numen.task.reflex.ReflexRegistry.enabled(id())) {
            return SurvivalDecisions.DORMANT;
        }

        ThreatContext context = threatContext(companion);
        long now = companion.level().getGameTime();
        if (!context.threats().isEmpty()) {
            lastThreatSeenGameTime = now;
            return SurvivalDecisions.mobDefensePriority(
                    true, shouldYieldForHealingFood(companion, context));
        }
        if (episodeActive && episodeLockedOrUnconfirmed(now)) {
            return SurvivalDecisions.mobDefensePriority(
                    true, shouldYieldForHealingFood(companion, context));
        }
        if (episodeActive) {
            // Keep one final scheduler tick so cleanup and outcome persistence
            // happen while this chain owns the physical lease. Priority probes
            // themselves must not halt another chain or mutate the world.
            return SurvivalDecisions.mobDefensePriority(
                    true, shouldYieldForHealingFood(companion, context));
        }
        return SurvivalDecisions.DORMANT;
    }

    @Override
    public void tick(NumenPlayer companion) {
        ThreatContext context = threatContext(companion);
        long now = companion.level().getGameTime();
        if (shouldYieldForHealingFood(companion, context)) {
            // FoodChain cannot start while a held shield/bow owns use-item.
            // Release only after its own separation/shelter predicate says the
            // eating window is safe; FoodChain wins on the next scheduler tick.
            stopNav();
            stopMelee();
            ranged.stop(companion);
            stopGuard();
            companion.stopUsingItem();
            InputDriver.halt(companion);
            mode = Mode.HOLD;
            return;
        }
        if (context.threats().isEmpty()) {
            boolean continuingSafety =
                    continueSafetyTransaction(companion, now);
            if (!continuingSafety) {
                tickQuietHold(companion);
            }
            if (episodeActive
                    && !continuingSafety
                    && !episodeLockedOrUnconfirmed(now)) {
                finishEpisode(companion, "quiet_window_secured");
            }
            return;
        }

        lastThreatSeenGameTime = now;
        startEpisode(companion, context);
        maxEngagedThreats = Math.max(maxEngagedThreats, context.threats().size());
        for (LivingEntity entity : context.threats()) {
            if (episodeEntities.size() >= MAX_EPISODE_ENTITIES
                    && !episodeEntities.containsKey(entity.getId())) {
                break;
            }
            episodeEntities.put(entity.getId(), entity);
        }
        CombatEquipment.prepareDefensiveLoadout(companion);

        refreshShelter(companion, isShelterMode());
        refreshTerrain(companion, context, false);
        CombatDecisionInput input = CombatDecisionInputFactory.create(
                companion,
                context.threats(),
                context.observations(),
                terrain.state(),
                context.directive());
        CombatDecision decision =
                com.dwinovo.numen.core.task.combat.CombatDecisionEngine.decide(input);
        lastDecision = decision;

        LivingEntity focus = selectFocus(companion, context, decision.focusThreat());
        if (focus == null) {
            tickQuietHold(companion);
            return;
        }
        lastFocus = focus;
        lastThreatPos = focus.blockPosition();
        if (context.directive() == EngagementDirective.EXPLICIT_COMBAT
                && focus instanceof Player
                && decision.hardVetoes().stream().anyMatch(veto ->
                        veto.code() == CombatDecision.VetoCode.PROTECTED_TARGET)) {
            CompanionTickDispatcher.stopActive(
                    companion,
                    "server survival policy refuses attacks on players");
        }

        PolicyProposal policy = policyProposal(
                companion,
                focus,
                context.observations().get(focus.getId()),
                decision,
                input);
        Action requested = policy == null ? decision.action() : policy.supervisorAction();
        Action action = applySupervisorAndHysteresis(companion, focus, requested);
        if (policy != null && action != policy.supervisorAction()) {
            episodePolicyFailed = true;
            episodePolicySafetyPreempted = true;
            policy = null;
        }
        currentPolicyAction = policy == null ? null : policy.kind();
        if (policy != null && action == policy.supervisorAction()) {
            if (policy.kind() == CombatPolicyAction.APPROACH) {
                tickPolicyApproach(companion, focus);
                return;
            }
            if (policy.kind() == CombatPolicyAction.MELEE_STRIKE) {
                tickPolicyStrike(companion, focus);
                return;
            }
        }
        switch (action) {
            case MELEE_ENGAGE -> tickMelee(companion, focus);
            case RANGED_ENGAGE -> tickRanged(companion, focus);
            case RETREAT -> tickRetreat(companion, context, focus);
            case SEEK_HARD_COVER -> tickCover(companion, focus);
            case SEEK_TRUSTED_SHELTER -> tickShelter(companion, context, focus);
            case HOLD_SAFE_POSITION, HOLD_DEFENSIVE_POSITION ->
                    tickHold(companion, focus, action == Action.HOLD_DEFENSIVE_POSITION);
            case NONE -> tickQuietHold(companion);
        }
    }

    @Override
    public void onInterrupt(NumenPlayer companion) {
        releasePhysical(companion);
        ThreatContext context = threatContext(companion);
        if (episodeActive
                && context.threats().isEmpty()
                && !episodeLockedOrUnconfirmed(companion.level().getGameTime())) {
            finishEpisode(companion, "quiet_window_secured");
        }
    }

    @Override
    public String name() {
        return "mob_defense";
    }

    @Override
    public com.dwinovo.numen.task.control.BodyControlClass controlClass() {
        return com.dwinovo.numen.task.control.BodyControlClass.DEFENSIVE_REFLEX;
    }

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "会结合血量、已穿护甲、背包武器/盾/食物、Buff、怪物动作与地形，实时决定战斗、掩护、撤退或进入已认证安全屋";
    }

    private Action applySupervisorAndHysteresis(
            NumenPlayer self, LivingEntity focus, Action requested) {
        long now = self.level().getGameTime();
        CombatObservation observation = cachedContext.observations().get(focus.getId());
        boolean primedCreeper = focus instanceof Creeper
                && observation != null
                && observation.intent().action() == CombatAction.AOE_CHARGE;
        if (primedCreeper) {
            return commitSafety(Action.RETREAT, now);
        }
        if (focus instanceof Creeper && now < creeperKiteUntil) {
            return Action.RETREAT;
        }
        boolean phantom = focus instanceof Phantom;
        Action serverDecision =
                lastDecision == null ? requested : lastDecision.action();
        boolean immediatePhantomDefense = phantom
                && (serverDecision == Action.MELEE_ENGAGE
                || serverDecision == Action.HOLD_DEFENSIVE_POSITION);
        if (immediatePhantomDefense) {
            committedSafety = null;
            safetyCommitUntil = 0L;
            return serverDecision;
        }
        if (phantom && requested == Action.SEEK_HARD_COVER) {
            requested = usableShelter(self)
                    ? Action.SEEK_TRUSTED_SHELTER : Action.RETREAT;
        }
        if (phantom && committedSafety == Action.SEEK_HARD_COVER) {
            committedSafety = null;
            safetyCommitUntil = 0L;
        }
        if (requested == Action.RANGED_ENGAGE && now < rangedBlockedUntil) {
            requested = !phantom && terrain.hardCoverTarget() != null
                    ? Action.SEEK_HARD_COVER : Action.RETREAT;
        }

        // Honour an already selected escape transaction before considering a
        // different equally-safe request. This prevents cover/retreat/shelter
        // oscillation from resetting navigation every tick.
        if (committedSafety != null && now < safetyCommitUntil) {
            if (isHold(committedSafety)) {
                committedSafety = null;
                safetyCommitUntil = 0L;
            } else {
                if (committedSafety == Action.SEEK_HARD_COVER
                        && (terrain == null
                        || terrain.hardCoverTarget() == null)) {
                    return commitSafety(Action.RETREAT, now);
                }
                if (committedSafety == Action.SEEK_TRUSTED_SHELTER
                        && !usableShelter(self)) {
                    return commitSafety(
                            !phantom
                                    && terrain != null
                                    && terrain.hardCoverTarget() != null
                                    ? Action.SEEK_HARD_COVER : Action.RETREAT,
                            now);
                }
                return committedSafety;
            }
        }
        if (isShelterMode() && usableShelter(self)) {
            return commitSafety(Action.SEEK_TRUSTED_SHELTER, now);
        }
        if (isSafety(requested)) {
            if (isHold(requested)) {
                committedSafety = null;
                safetyCommitUntil = 0L;
                return requested;
            }
            return commitSafety(requested, now);
        }
        committedSafety = null;
        return requested;
    }

    private Action commitSafety(Action action, long now) {
        committedSafety = action;
        safetyCommitUntil = now + SAFETY_COMMIT_TICKS;
        return action;
    }

    private boolean usableShelter(NumenPlayer self) {
        if (shelter == null || self.level().getGameTime() < shelterBlockedUntil) {
            return false;
        }
        boolean inside = isInside(shelter, self.blockPosition());
        return shelter.facts().enclosed()
                && shelter.facts().spawnSafe()
                && (inside || shelter.facts().reachable() && shelter.doorwayClear())
                && !shelter.hostileInside();
    }

    private static boolean isSafety(Action action) {
        return action == Action.RETREAT
                || action == Action.SEEK_HARD_COVER
                || action == Action.SEEK_TRUSTED_SHELTER
                || action == Action.HOLD_SAFE_POSITION
                || action == Action.HOLD_DEFENSIVE_POSITION;
    }

    private static boolean isHold(Action action) {
        return action == Action.HOLD_SAFE_POSITION
                || action == Action.HOLD_DEFENSIVE_POSITION;
    }

    private PolicyProposal policyProposal(
            NumenPlayer self,
            LivingEntity focus,
            CombatObservation observation,
            CombatDecision decision,
            CombatDecisionInput input) {
        if (observation == null
                || observation.intent() == null
                || episodePolicyFailed) {
            return null;
        }
        Optional<CombatPolicySnapshot> active;
        try {
            active = CombatPolicyRuntime.activePolicy(
                    self,
                    entityType(focus),
                    observation.adapter(),
                    observation.schema());
        } catch (RuntimeException ignored) {
            return null;
        }
        if (active.isEmpty()) return null;
        CombatPolicySnapshot snapshot = active.orElseThrow();
        if (policyTargetUuid != null
                && !policyTargetUuid.equals(focus.getUUID())) {
            // One exact target per episode keeps candidate evaluation bounded
            // and makes success/failure attribution unambiguous.
            return null;
        }
        if (policyCursor == null
                || policySnapshot == null
                || policySnapshot.revision() != snapshot.revision()
                || !focus.getUUID().equals(policyTargetUuid)) {
            policySnapshot = snapshot;
            policyCursor = new CombatPolicyCursor(snapshot.policy());
            policyTargetUuid = focus.getUUID();
            episodePolicyTicks = 0;
        }
        if (episodePolicyTicks >= MAX_POLICY_EPISODE_TICKS) {
            episodePolicyFailed = true;
            return null;
        }

        Optional<CombatPolicyCursor.Selection> selected = policyCursor.tick(
                observation.intent().action().name(),
                Math.max(0.0, self.distanceTo(focus)),
                Math.max(0.0, Math.min(1.0, self.getHealth() / self.getMaxHealth())));
        if (selected.isEmpty()) return null;

        CombatPolicyAction kind = selected.orElseThrow().action();
        Action mapped = switch (kind) {
            case APPROACH, MELEE_STRIKE -> Action.MELEE_ENGAGE;
            case RETREAT -> Action.RETREAT;
            case GUARD -> Action.HOLD_DEFENSIVE_POSITION;
            case HOLD -> Action.HOLD_SAFE_POSITION;
            case SEEK_COVER -> Action.SEEK_HARD_COVER;
            case SEEK_SHELTER -> Action.SEEK_TRUSTED_SHELTER;
            case RANGED_SHOT -> Action.RANGED_ENGAGE;
        };
        episodePolicyId = snapshot.policy().id();
        episodePolicyTicks++;
        com.dwinovo.numen.core.task.combat.CombatPolicyExecutionTracker.mark(
                self, episodePolicyId);
        if (policySafetyPreempts(
                decision, input, observation, kind, mapped)) {
            episodePolicyFailed = true;
            episodePolicySafetyPreempted = true;
            return null;
        }

        return new PolicyProposal(kind, mapped);
    }

    private static boolean policySafetyPreempts(
            CombatDecision decision,
            CombatDecisionInput input,
            CombatObservation observation,
            CombatPolicyAction policyAction,
            Action proposed) {
        boolean executable = switch (policyAction) {
            case GUARD -> input.self().loadout().hasShield();
            case SEEK_COVER -> input.terrain().hardCoverReachable();
            case SEEK_SHELTER -> input.terrain().canReachVerifiedShelter();
            case HOLD -> input.terrain().isSecureInsideShelter()
                    || !observation.facts().lineOfSightToObserver()
                    && observation.facts().distance() >= 8.0;
            default -> true;
        };
        if (!executable) return true;
        boolean mandatoryServerSafety = isSafety(decision.action())
                && (decision.risk() == CombatDecision.Risk.CRITICAL
                        || input.self().vitals().effectiveHealthRatio() <= 0.35
                        || decision.hardVetoes().stream().anyMatch(veto ->
                        veto.scope() == CombatDecision.VetoScope.ANY_ENGAGEMENT)
                        || observation.intent().action() == CombatAction.AOE_CHARGE
                        || observation.intent().action()
                        == CombatAction.PROJECTILE_RELEASE);
        if (mandatoryServerSafety && proposed != decision.action()) {
            return true;
        }
        return (proposed == Action.MELEE_ENGAGE
                || proposed == Action.RANGED_ENGAGE)
                && decision.isVetoed(proposed);
    }

    private void tickPolicyApproach(
            NumenPlayer self, LivingEntity threat) {
        enterMode(self, Mode.MELEE);
        stopMelee();
        ranged.stop(self);
        stopGuard();
        if (self.distanceTo(threat) <= 3.75) {
            stopNav();
            InputDriver.halt(self);
            InputDriver.lookAt(self, threat.getEyePosition());
            return;
        }
        if (nav == null || navTargetId != threat.getId()) {
            stopNav();
            navTargetId = threat.getId();
            nav = new PlayerNav(
                    self,
                    threat::blockPosition,
                    CHASE_SPEED,
                    () -> self.distanceTo(threat) <= 3.75);
        }
        if (nav.tick() == PlayerNav.Status.FAILED) {
            stopNav();
            episodePolicyFailed = true;
            episodePolicySafetyPreempted = true;
            commitSafety(Action.RETREAT, self.level().getGameTime());
        }
    }

    private void tickPolicyStrike(
            NumenPlayer self, LivingEntity threat) {
        enterMode(self, Mode.MELEE);
        stopNav();
        ranged.stop(self);
        stopGuard();
        ToolSelect.holdBestWeapon(self);
        if (!inReach(self, threat)) {
            stopMelee();
            InputDriver.halt(self);
            InputDriver.lookAt(self, threat.getEyePosition());
            return;
        }
        if (melee == null || meleeTargetId != threat.getId()) {
            stopMelee();
            meleeTargetId = threat.getId();
            melee = Interaction.attackEntity(self, threat);
        }
        Interaction.Status status = melee.tick();
        if (status == Interaction.Status.DONE) {
            meleeHits++;
            stopMelee();
        } else if (status == Interaction.Status.FAILED) {
            stopMelee();
            episodePolicyFailed = true;
        }
    }

    private void tickMelee(NumenPlayer self, LivingEntity threat) {
        enterMode(self, Mode.MELEE);
        ranged.stop(self);
        CombatObservation observation = cachedContext == null
                ? null : cachedContext.observations().get(threat.getId());
        boolean committedRangedShot = threat instanceof AbstractSkeleton
                && observation != null
                && (observation.intent().action() == CombatAction.RANGED_CHARGE
                || observation.intent().action() == CombatAction.PROJECTILE_RELEASE)
                && self.getOffhandItem().getItem()
                instanceof net.minecraft.world.item.ShieldItem;
        if (committedRangedShot) {
            stopNav();
            stopMelee();
            tickGuard(self, threat);
            return;
        }
        stopGuard();
        ToolSelect.holdBestWeapon(self);
        if (inReach(self, threat)) {
            stopNav();
            if (melee == null || meleeTargetId != threat.getId()) {
                stopMelee();
                meleeTargetId = threat.getId();
                melee = Interaction.attackEntity(self, threat);
            }
            Interaction.Status status = melee.tick();
            if (status == Interaction.Status.DONE) {
                meleeHits++;
                if (threat instanceof Creeper) {
                    creeperKiteUntil = self.level().getGameTime() + CREEPER_KITE_TICKS;
                }
                stopMelee();
            } else if (status == Interaction.Status.FAILED) {
                stopMelee();
            }
            return;
        }
        stopMelee();
        if (nav == null || navTargetId != threat.getId()) {
            stopNav();
            navTargetId = threat.getId();
            nav = new PlayerNav(
                    self, threat::blockPosition, CHASE_SPEED, () -> inReach(self, threat));
        }
        switch (nav.tick()) {
            case RUNNING, ARRIVED -> consecutiveNavFails = 0;
            case FAILED -> {
                stopNav();
                if (++consecutiveNavFails >= MAX_NAV_FAILURES) {
                    safetyCommitUntil = self.level().getGameTime() + SAFETY_COMMIT_TICKS;
                    committedSafety = Action.RETREAT;
                    consecutiveNavFails = 0;
                }
            }
        }
    }

    private void tickRanged(NumenPlayer self, LivingEntity threat) {
        enterMode(self, Mode.RANGED);
        stopGuard();
        stopMelee();
        if (self.distanceTo(threat) < 5.0) {
            committedSafety = Action.RETREAT;
            safetyCommitUntil = self.level().getGameTime() + SAFETY_COMMIT_TICKS;
            tickRetreat(self, cachedContext, threat);
            return;
        }
        CombatRangedExecutor.Status status = ranged.tick(self, threat);
        if (status == CombatRangedExecutor.Status.FIRED) {
            rangedShots++;
        } else if (status == CombatRangedExecutor.Status.FAILED) {
            if (currentPolicyAction == CombatPolicyAction.RANGED_SHOT) {
                episodePolicyFailed = true;
            }
            rangedBlockedUntil =
                    self.level().getGameTime() + FAILED_RANGED_HOLD_TICKS;
            if (threat instanceof Phantom) {
                commitSafety(Action.RETREAT, self.level().getGameTime());
                tickRetreat(self, cachedContext, threat);
            } else {
                tickCover(self, threat);
            }
        }
    }

    private void tickRetreat(
            NumenPlayer self, ThreatContext context, LivingEntity focus) {
        enterMode(self, Mode.RETREAT);
        stopMelee();
        ranged.stop(self);
        stopGuard();
        if (tickPendingDoorClose(self)) return;
        refreshTerrain(self, context, consecutiveNavFails > 0);
        BlockPos destination = terrain.escapeTarget();
        if (nav == null) {
            if (destination != null && !blindRetreat) {
                nav = PlayerNav.toGoal(
                        self,
                        () -> NavGoal.nearGround(destination, 1.0),
                        SAFETY_SPEED,
                        () -> focusGoneOrFar(self, focus));
            } else {
                BlockPos danger = lastThreatPos == null
                        ? self.blockPosition() : lastThreatPos;
                int maintainY = self.blockPosition().getY();
                blindRetreat = true;
                nav = PlayerNav.toGoal(
                        self,
                        () -> NavGoal.runAway(danger, maintainY),
                        SAFETY_SPEED,
                        () -> focusGoneOrFar(self, focus));
            }
        }
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED -> {
                stopNav();
                consecutiveNavFails = 0;
                blindRetreat = false;
                terrainTick = Long.MIN_VALUE;
            }
            case FAILED -> {
                stopNav();
                consecutiveNavFails++;
                terrainTick = Long.MIN_VALUE;
                if (consecutiveNavFails >= MAX_NAV_FAILURES) {
                    blindRetreat = true;
                    consecutiveNavFails = 0;
                }
            }
        }
    }

    private void tickCover(NumenPlayer self, LivingEntity focus) {
        enterMode(self, Mode.COVER);
        stopMelee();
        ranged.stop(self);
        if (tickPendingDoorClose(self)) return;
        BlockPos cover = terrain == null ? null : terrain.hardCoverTarget();
        if (cover == null) {
            if (currentPolicyAction == CombatPolicyAction.SEEK_COVER) {
                episodePolicyFailed = true;
            }
            commitSafety(Action.RETREAT, self.level().getGameTime());
            tickRetreat(self, cachedContext, focus);
            return;
        }
        if (self.blockPosition().distSqr(cover) <= 2.0) {
            stopNav();
            tickGuard(self, focus);
            return;
        }
        stopGuard();
        if (nav == null) {
            nav = PlayerNav.toGoal(
                    self,
                    () -> NavGoal.nearGround(cover, 1.0),
                    SAFETY_SPEED,
                    () -> self.blockPosition().distSqr(cover) <= 2.0);
        }
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED -> {
                stopNav();
                consecutiveNavFails = 0;
            }
            case FAILED -> {
                stopNav();
                terrainTick = Long.MIN_VALUE;
                if (++consecutiveNavFails >= MAX_NAV_FAILURES) {
                    consecutiveNavFails = 0;
                    commitSafety(Action.RETREAT, self.level().getGameTime());
                    tickRetreat(self, cachedContext, focus);
                }
            }
        }
    }

    private void tickShelter(
            NumenPlayer self, ThreatContext context, LivingEntity focus) {
        refreshShelter(self, true);
        boolean currentlyInside =
                shelter != null && isInside(shelter, self.blockPosition());
        if (shelter == null
                || !shelter.facts().enclosed()
                || !shelter.facts().spawnSafe()
                || !currentlyInside && (!shelter.facts().reachable()
                || !shelter.doorwayClear()
                || shelter.hostileInside())
                || !currentlyInside
                && self.level().getGameTime() < shelterBlockedUntil) {
            if (currentPolicyAction == CombatPolicyAction.SEEK_SHELTER) {
                episodePolicyFailed = true;
            }
            fallbackFromShelter(self, context, focus, !currentlyInside);
            return;
        }
        if (currentlyInside) {
            if (mode == Mode.SHELTER_SETTLE) {
                tickShelterSettle(self, focus);
                return;
            }
            enterMode(self, Mode.SECURE_DOOR);
            tickSecureDoor(self, focus);
            return;
        }

        LiveShelter current = shelter;
        if (mode != Mode.SHELTER_CROSS) {
            enterMode(self, Mode.SHELTER_APPROACH);
        }
        if (mode == Mode.SHELTER_APPROACH) {
            BlockPos outside = current.doorway().outside();
            if (self.blockPosition().distSqr(outside) > 2.0) {
                if (nav == null) {
                    nav = PlayerNav.toGoal(
                            self,
                            () -> NavGoal.nearGround(outside, 1.0),
                            SAFETY_SPEED,
                            () -> self.blockPosition().distSqr(outside) <= 2.0);
                }
                if (nav.tick() == PlayerNav.Status.FAILED) {
                    stopNav();
                    if (++consecutiveNavFails >= MAX_NAV_FAILURES) {
                        consecutiveNavFails = 0;
                        fallbackFromShelter(self, context, focus, true);
                    }
                }
                return;
            }
            stopNav();
            if (!setDoorOpen(self, current, true)) {
                return;
            }
            enterMode(self, Mode.SHELTER_CROSS);
        }

        if (mode == Mode.SHELTER_CROSS) {
            if (!doorIsOpen(self, current.doorway().lower())
                    && !setDoorOpen(self, current, true)) {
                return;
            }
            BlockPos inside = current.doorway().inside();
            if (self.blockPosition().distSqr(inside) <= 2.0
                    || isInside(current, self.blockPosition())) {
                stopNav();
                enterMode(self, Mode.SECURE_DOOR);
                tickSecureDoor(self, focus);
                return;
            }
            if (nav == null) {
                nav = PlayerNav.toGoal(
                        self,
                        () -> NavGoal.nearGround(inside, 0.75),
                        SAFETY_SPEED,
                        () -> isInside(current, self.blockPosition()));
            }
            if (nav.tick() == PlayerNav.Status.FAILED) {
                stopNav();
                if (++consecutiveNavFails >= MAX_NAV_FAILURES) {
                    consecutiveNavFails = 0;
                    setDoorOpen(self, current, false);
                    fallbackFromShelter(self, context, focus, true);
                }
            }
        }
    }

    private void fallbackFromShelter(
            NumenPlayer self,
            ThreatContext context,
            LivingEntity focus,
            boolean blockShelter) {
        if (blockShelter) {
            shelterBlockedUntil =
                    self.level().getGameTime() + SHELTER_FAILURE_COOLDOWN_TICKS;
        }
        Action fallback = !(focus instanceof Phantom)
                && terrain != null
                && terrain.hardCoverTarget() != null
                ? Action.SEEK_HARD_COVER : Action.RETREAT;
        commitSafety(fallback, self.level().getGameTime());
        if (fallback == Action.SEEK_HARD_COVER) tickCover(self, focus);
        else tickRetreat(self, context, focus);
    }

    private boolean tickPendingDoorClose(NumenPlayer self) {
        if (!pendingDoorClose || shelter == null) return false;
        BlockPos door = shelter.doorway().lower();
        if (self.distanceToSqr(Vec3.atCenterOf(door)) > 4.5 * 4.5
                || !doorIsOpen(self, door)) {
            pendingDoorClose = false;
            return false;
        }
        if (setDoorOpen(self, shelter, false)) return false;
        InputDriver.halt(self);
        return true;
    }

    private void tickSecureDoor(NumenPlayer self, LivingEntity focus) {
        if (shelter == null) {
            tickHold(self, focus, true);
            return;
        }
        enterMode(self, Mode.SECURE_DOOR);
        BlockPos lower = shelter.doorway().lower();
        if (doorIsOpen(self, lower)) {
            if (self.level().getGameTime() < shelterBlockedUntil) {
                tickGuard(self, focus);
                return;
            }
            if (self.distanceToSqr(Vec3.atCenterOf(lower)) > 4.25 * 4.25) {
                BlockPos inside = shelter.doorway().inside();
                if (nav == null) {
                    nav = PlayerNav.toGoal(
                            self,
                            () -> NavGoal.nearGround(inside, 0.75),
                            1.0,
                            () -> self.distanceToSqr(Vec3.atCenterOf(lower))
                                    <= 4.25 * 4.25);
                }
                PlayerNav.Status status = nav.tick();
                if (status == PlayerNav.Status.FAILED) {
                    stopNav();
                    shelterBlockedUntil = self.level().getGameTime()
                            + SHELTER_FAILURE_COOLDOWN_TICKS;
                    tickGuard(self, focus);
                }
                return;
            }
            stopNav();
            setDoorOpen(self, shelter, false);
            return;
        }
        stopNav();
        pendingDoorClose = false;
        if (shelter.hostileInside()) {
            committedSafety = null;
            safetyCommitUntil = 0L;
            enterMode(self, Mode.HOLD);
            tickHold(self, focus, true);
            return;
        }
        enterMode(self, Mode.SHELTER_SETTLE);
        tickShelterSettle(self, focus);
    }

    private void tickShelterSettle(NumenPlayer self, LivingEntity focus) {
        if (shelter == null || !isInside(shelter, self.blockPosition())) {
            enterMode(self, Mode.HOLD);
            tickHold(self, focus, true);
            return;
        }
        BlockPos stance = shelter.stance();
        if (self.blockPosition().distSqr(stance) <= 2.0) {
            stopNav();
            enterMode(self, Mode.HOLD);
            shelterTick = Long.MIN_VALUE;
            tickHold(self, focus, false);
            return;
        }
        if (nav == null) {
            nav = PlayerNav.toGoal(
                    self,
                    () -> NavGoal.nearGround(stance, 0.75),
                    1.0,
                    () -> self.blockPosition().distSqr(stance) <= 2.0);
        }
        PlayerNav.Status status = nav.tick();
        if (status == PlayerNav.Status.FAILED) {
            stopNav();
            enterMode(self, Mode.HOLD);
            tickHold(self, focus, true);
        }
    }

    private boolean setDoorOpen(
            NumenPlayer self, LiveShelter targetShelter, boolean desiredOpen) {
        BlockPos lower = targetShelter.doorway().lower();
        BlockState state = self.level().getBlockState(lower);
        if (!(state.getBlock() instanceof DoorBlock door)
                || !door.type().canOpenByHand()
                || !state.hasProperty(BlockStateProperties.OPEN)) {
            failDoorAttempt(self, lower, desiredOpen);
            return false;
        }
        if (!lower.equals(doorAttemptPos)
                || doorAttemptGoal == null
                || doorAttemptGoal != desiredOpen) {
            doorAttemptPos = lower.immutable();
            doorAttemptGoal = desiredOpen;
            doorFailures = 0;
            stopDoorInteraction();
        }
        if (state.getValue(BlockStateProperties.OPEN) == desiredOpen) {
            stopDoorInteraction();
            doorFailures = 0;
            pendingDoorClose = desiredOpen;
            return true;
        }
        if (self.distanceToSqr(Vec3.atCenterOf(lower)) > 4.5 * 4.5) {
            stopDoorInteraction();
            return false;
        }
        if (doorInteraction == null || doorInteractionGoal == null
                || doorInteractionGoal != desiredOpen) {
            stopDoorInteraction();
            doorInteractionGoal = desiredOpen;
            doorInteraction =
                    Interaction.useBlock(self, lower, InteractionHand.MAIN_HAND);
        }
        Interaction.Status result = doorInteraction.tick();
        BlockState after = self.level().getBlockState(lower);
        boolean reached = after.getBlock() instanceof DoorBlock
                && after.hasProperty(BlockStateProperties.OPEN)
                && after.getValue(BlockStateProperties.OPEN) == desiredOpen;
        if (reached) {
            stopDoorInteraction();
            doorFailures = 0;
            pendingDoorClose = desiredOpen;
            return true;
        }
        if (result != Interaction.Status.RUNNING) {
            stopDoorInteraction();
            failDoorAttempt(self, lower, desiredOpen);
        }
        return false;
    }

    private void failDoorAttempt(
            NumenPlayer self, BlockPos lower, boolean desiredOpen) {
        if (!lower.equals(doorAttemptPos)
                || doorAttemptGoal == null
                || doorAttemptGoal != desiredOpen) {
            doorAttemptPos = lower.immutable();
            doorAttemptGoal = desiredOpen;
            doorFailures = 0;
        }
        if (++doorFailures >= MAX_DOOR_FAILURES) {
            shelterBlockedUntil =
                    self.level().getGameTime() + SHELTER_FAILURE_COOLDOWN_TICKS;
            stopDoorInteraction();
            if (!desiredOpen) pendingDoorClose = false;
        }
    }

    private void tickHold(
            NumenPlayer self, LivingEntity focus, boolean defensive) {
        enterMode(self, Mode.HOLD);
        stopNav();
        stopMelee();
        ranged.stop(self);
        if (shelter != null
                && isInside(shelter, self.blockPosition())
                && doorIsOpen(self, shelter.doorway().lower())) {
            enterMode(self, Mode.SECURE_DOOR);
            tickSecureDoor(self, focus);
            return;
        }
        if (defensive && focus != null && !(focus instanceof Creeper)) {
            tickGuard(self, focus);
        } else {
            stopGuard();
            InputDriver.halt(self);
        }
    }

    private void tickGuard(NumenPlayer self, LivingEntity focus) {
        if (focus != null) {
            InputDriver.lookAt(self, focus.getEyePosition());
        }
        if (self.getOffhandItem().getItem()
                instanceof net.minecraft.world.item.ShieldItem) {
            if (guard == null) {
                guard = Interaction.useInAir(
                        self, InteractionHand.OFF_HAND, Interaction.Timing.hold());
            }
            if (guard.tick() != Interaction.Status.RUNNING) {
                stopGuard();
            }
        } else {
            InputDriver.halt(self);
        }
    }

    private void tickQuietHold(NumenPlayer self) {
        refreshShelter(self, isShelterMode());
        if (shelter != null
                && isInside(shelter, self.blockPosition())
                && doorIsOpen(self, shelter.doorway().lower())) {
            enterMode(self, Mode.SECURE_DOOR);
            setDoorOpen(self, shelter, false);
            return;
        }
        enterMode(self, Mode.HOLD);
        stopNav();
        stopMelee();
        ranged.stop(self);
        stopGuard();
        InputDriver.halt(self);
    }

    /**
     * Keep an already-started escape/door transaction alive through the threat
     * lease. A despawned or briefly occluded mob must not leave momo frozen in
     * a doorway with the door open.
     */
    private boolean continueSafetyTransaction(NumenPlayer self, long now) {
        if (!episodeActive) return false;
        if (isShelterMode()) {
            refreshShelter(self, true);
            if (shelter != null) {
                tickShelter(self, cachedContext, lastFocus);
                return true;
            }
        }
        if (pendingDoorClose && shelter != null) {
            commitSafety(Action.RETREAT, now);
            tickRetreat(self, cachedContext, lastFocus);
            return true;
        }
        if (now < safetyCommitUntil && mode == Mode.COVER) {
            tickCover(self, lastFocus);
            return true;
        }
        if (now < safetyCommitUntil && mode == Mode.RETREAT) {
            tickRetreat(self, cachedContext, lastFocus);
            return true;
        }
        return false;
    }

    private ThreatContext threatContext(NumenPlayer self) {
        long now = self.level().getGameTime();
        if (contextTick == now && cachedContext != null) return cachedContext;

        EngagementDirective directive = EngagementDirective.SURVIVAL_ONLY;
        TaskRecord task = CompanionTickDispatcher.asyncTaskFor(self.getUUID());
        List<Integer> explicitIds = List.of();
        if (task instanceof MeleeAttackTaskRecord meleeTask) {
            explicitIds = meleeTask.entityIds;
            directive = EngagementDirective.EXPLICIT_COMBAT;
        } else if (task instanceof RangedAttackTaskRecord rangedTask) {
            explicitIds = rangedTask.entityIds;
            directive = EngagementDirective.EXPLICIT_COMBAT;
        }

        Map<Integer, LivingEntity> found = new LinkedHashMap<>();
        LivingEntity recent = ThreatMemory.resolveRecentAttacker(self);
        if (!episodeActive
                && recent == null
                && explicitIds.isEmpty()
                && now < nextIdleScanTick) {
            cachedContext = new ThreatContext(
                    List.of(), Map.of(), directive);
            contextTick = now;
            return cachedContext;
        }
        if (!episodeActive && recent == null && explicitIds.isEmpty()) {
            nextIdleScanTick = now + IDLE_SCAN_INTERVAL_TICKS;
        }
        AABB scan = self.getBoundingBox().inflate(SCAN_RADIUS);
        for (Mob mob : self.level().getEntitiesOfClass(Mob.class, scan)) {
            if (!mob.isAlive()) continue;
            if (mob.getTarget() == self || mob == recent) {
                found.put(mob.getId(), mob);
            }
        }
        if (recent != null
                && recent.isAlive()
                && recent.level() == self.level()
                && self.distanceToSqr(recent)
                <= RECENT_ATTACKER_RADIUS * RECENT_ATTACKER_RADIUS) {
            found.put(recent.getId(), recent);
        }

        if (!explicitIds.isEmpty() && self.level() instanceof ServerLevel level) {
            boolean safelySheltered = shelter != null
                    && isInside(shelter, self.blockPosition())
                    && shelter.facts().enclosed()
                    && shelter.facts().spawnSafe()
                    && !doorIsOpen(self, shelter.doorway().lower());
            for (int id : explicitIds) {
                if (level.getEntity(id) instanceof LivingEntity living
                        && living.isAlive()
                        && living != self
                        && self.distanceToSqr(living)
                        <= EXPLICIT_TARGET_RADIUS * EXPLICIT_TARGET_RADIUS) {
                    boolean activelyThreatening =
                            living instanceof Mob mob && mob.getTarget() == self;
                    if (safelySheltered && !activelyThreatening && living != recent) {
                        continue;
                    }
                    found.put(id, living);
                }
            }
        }

        List<LivingEntity> threats = List.copyOf(found.values());
        Map<Integer, CombatObservation> observations = new LinkedHashMap<>();
        CombatObservationService observer = CombatObservationService.instance();
        for (LivingEntity threat : threats) {
            try {
                observations.put(threat.getId(), observer.observe(self, threat));
            } catch (RuntimeException ignored) {
                // The entity may have crossed dimensions or died during this tick.
            }
        }
        cachedContext = new ThreatContext(
                threats, Map.copyOf(observations), directive);
        contextTick = now;
        return cachedContext;
    }

    private void refreshShelter(NumenPlayer self, boolean force) {
        long now = self.level().getGameTime();
        // Door openness and occupancy are checked directly by the active
        // transaction. Full blueprint/live-shell verification is deliberately
        // capped at once per second even while sheltering.
        int interval = SHELTER_REFRESH_TICKS;
        if (shelterTick == now
                || (shelterTick != Long.MIN_VALUE
                && now - shelterTick < interval)) {
            return;
        }
        shelter = CombatShelterResolver.latest(self).orElse(null);
        shelterTick = now;
    }

    private void refreshTerrain(
            NumenPlayer self, ThreatContext context, boolean force) {
        long now = self.level().getGameTime();
        if (!force && terrain != null
                && now - terrainTick < TERRAIN_REFRESH_TICKS) {
            return;
        }
        CombatTerrainProbe.ShelterFacts facts = shelter == null
                ? CombatTerrainProbe.ShelterFacts.none() : shelter.facts();
        if (shelter != null && shelter.hostileInside()) {
            // Preserve structural diagnostics on LiveShelter, but never expose
            // an occupied interior to the decision engine as secure/reachable.
            facts = new CombatTerrainProbe.ShelterFacts(
                    false,
                    false,
                    shelter.facts().distance(),
                    shelter.facts().enclosed(),
                    shelter.facts().spawnSafe(),
                    false,
                    null,
                    null);
        }
        terrain = CombatTerrainProbe.inspect(self, context.threats(), facts);
        terrainTick = now;
    }

    private LivingEntity selectFocus(
            NumenPlayer self, ThreatContext context, ThreatType desiredType) {
        return context.threats().stream()
                .filter(entity -> desiredType == null || typeOf(entity) == desiredType)
                .max(Comparator.comparingDouble(entity -> focusScore(
                        self, entity, context.observations().get(entity.getId()))))
                .orElseGet(() -> context.threats().stream()
                        .min(Comparator.comparingDouble(self::distanceToSqr))
                        .orElse(null));
    }

    private static double focusScore(
            NumenPlayer self, LivingEntity entity, CombatObservation observation) {
        double score = Math.max(0.0, 32.0 - self.distanceTo(entity));
        if (entity instanceof Mob mob && mob.getTarget() == self) score += 20.0;
        if (observation != null) {
            score += switch (observation.intent().action()) {
                case AOE_CHARGE -> 40.0;
                case PROJECTILE_RELEASE -> 35.0;
                case DASH, RANGED_CHARGE -> 25.0;
                case MELEE_WINDUP -> 18.0;
                default -> 0.0;
            };
        }
        return score;
    }

    private static ThreatType typeOf(LivingEntity entity) {
        if (entity instanceof Creeper) return ThreatType.CREEPER;
        if (entity instanceof AbstractSkeleton) return ThreatType.SKELETON;
        if (entity instanceof Spider) return ThreatType.SPIDER;
        if (entity instanceof Phantom) return ThreatType.PHANTOM;
        if (entity instanceof Zombie) return ThreatType.ZOMBIE;
        if (entity instanceof Player) return ThreatType.PLAYER;
        if (entity instanceof IronGolem) return ThreatType.IRON_GOLEM;
        return ThreatType.OTHER_HOSTILE;
    }

    private boolean shouldYieldForHealingFood(
            NumenPlayer self, ThreatContext context) {
        if (self.getHealth() > SurvivalDecisions.LOW_HEALTH
                || self.getFoodData().getFoodLevel()
                >= SurvivalDecisions.REGEN_FOOD_LEVEL
                || !FoodChain.hasEdible(self)) {
            return false;
        }
        boolean liveSheltered = shelter != null
                && isInside(shelter, self.blockPosition())
                && shelter.facts().enclosed()
                && shelter.facts().spawnSafe()
                && !shelter.hostileInside()
                && !doorIsOpen(self, shelter.doorway().lower());
        boolean safelySeparated;
        if (context.threats().isEmpty()) {
            safelySeparated = episodeActive;
        } else if (liveSheltered) {
            safelySeparated = context.threats().stream().allMatch(threat -> {
                CombatObservation observation =
                        context.observations().get(threat.getId());
                boolean primed = observation != null
                        && observation.intent().action()
                        == CombatAction.AOE_CHARGE;
                double minimum = threat instanceof Creeper ? 8.0 : 4.0;
                return !isInside(shelter, threat.blockPosition())
                        && self.distanceTo(threat) >= minimum
                        && !threat.hasLineOfSight(self)
                        && !primed;
            });
        } else {
            safelySeparated = context.threats().stream().allMatch(threat ->
                    self.distanceTo(threat) >= SAFE_MEAL_DISTANCE
                            && !threat.hasLineOfSight(self));
        }
        if (!safelySeparated) return false;
        ThreatMemory.DamageSnapshot damage = ThreatMemory.recent(self);
        return damage == null
                || self.level().getGameTime() - damage.gameTime()
                >= QUIET_BEFORE_MEAL_TICKS;
    }

    private boolean episodeLockedOrUnconfirmed(long now) {
        return now - episodeStartGameTime < MIN_EPISODE_LOCK_TICKS
                || now - lastThreatSeenGameTime < QUIET_CONFIRM_TICKS;
    }

    private void startEpisode(NumenPlayer self, ThreatContext context) {
        if (episodeActive) return;
        episodeActive = true;
        episodeStartPosition = self.position();
        episodeStartHealth = self.getHealth();
        episodeStartGameTime = self.level().getGameTime();
        lastThreatSeenGameTime = episodeStartGameTime;
        LivingEntity first = context.threats().get(0);
        episodeThreatType = entityType(first);
        maxEngagedThreats = context.threats().size();
        episodeEntities.clear();
        meleeHits = 0;
        rangedShots = 0;

        Map<String, Object> data =
                new LinkedHashMap<>(CompanionEventBus.bodySnapshot(self));
        data.put("threat_type", episodeThreatType);
        data.put("threat_entity_id", first.getId());
        data.put("threat_entity_uuid", first.getUUID().toString());
        data.put("threat_position", List.of(first.getX(), first.getY(), first.getZ()));
        data.put("engaged_threats", context.threats().size());
        data.put("directive", context.directive().name().toLowerCase(Locale.ROOT));
        CombatObservation observation = context.observations().get(first.getId());
        if (observation != null) {
            data.put("observed_action",
                    observation.intent().action().name().toLowerCase(Locale.ROOT));
            data.put("adapter", observation.adapter());
            data.put("policy_schema", observation.schema());
            data.put("confidence", observation.intent().confidence());
        }
        CompanionEventBus.publish(
                self,
                "defense_started",
                CompanionEventBus.PRIORITY_URGENT,
                "The server survival director took control after observing an active threat.",
                data);
    }

    private void finishEpisode(NumenPlayer self, String outcome) {
        if (!episodeActive) return;
        Mode finalMode = mode;
        releasePhysical(self);
        int defeated = (int) episodeEntities.values().stream()
                .filter(entity -> entity.isRemoved() || entity.isDeadOrDying())
                .count();
        double healthLoss = Math.max(0.0, episodeStartHealth - self.getHealth());
        Boolean policySuccess = null;
        if (episodePolicyId != null) {
            policySuccess = !episodePolicyFailed
                    && self.isAlive()
                    && healthLoss <= 8.0
                    && ("quiet_window_secured".equals(outcome) || defeated > 0);
            try {
                CombatPolicyRuntime.recordExecutionOutcome(
                        self,
                        episodePolicyId,
                        policySuccess,
                        policySuccess
                                ? "server-confirmed survival episode; health_loss="
                                + String.format(Locale.ROOT, "%.2f", healthLoss)
                                : "supervisor/preemption or excessive damage; health_loss="
                                + String.format(Locale.ROOT, "%.2f", healthLoss));
            } catch (RuntimeException ignored) {
                // A user may have disabled/revised the policy during combat.
            }
        }
        com.dwinovo.numen.core.task.combat.CombatPolicyExecutionTracker.clear(self);
        double displaced = episodeStartPosition == null
                ? 0.0 : episodeStartPosition.distanceTo(self.position());
        Map<String, Object> data =
                new LinkedHashMap<>(CompanionEventBus.bodySnapshot(self));
        data.put("health_before", episodeStartHealth);
        data.put("health_after", self.getHealth());
        data.put("displaced_distance", displaced);
        data.put("duration_ticks",
                Math.max(0L, self.level().getGameTime() - episodeStartGameTime));
        data.put("outcome", outcome);
        data.put("threat_type", episodeThreatType);
        data.put("max_engaged_threats", maxEngagedThreats);
        data.put("defeated_threats", defeated);
        data.put("melee_hits", meleeHits);
        data.put("ranged_shots", rangedShots);
        data.put("response", finalMode.name().toLowerCase(Locale.ROOT));
        List<Map<String, Object>> observedTargets = new ArrayList<>();
        for (LivingEntity entity : episodeEntities.values()) {
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("entity_id", entity.getId());
            target.put("entity_uuid", entity.getUUID().toString());
            target.put("entity_type", entityType(entity));
            target.put("defeated", entity.isRemoved() || entity.isDeadOrDying());
            List<CombatObservation> trace =
                    CombatObservationService.instance().trace(
                            self.getUUID(), entity.getUUID());
            target.put("trace_available", !trace.isEmpty());
            if (!trace.isEmpty()) {
                CombatObservation latest = trace.get(trace.size() - 1);
                target.put("adapter", latest.adapter());
                target.put("policy_schema", latest.schema());
                target.put(
                        "last_observed_action",
                        latest.intent().action().name().toLowerCase(Locale.ROOT));
            }
            observedTargets.add(Map.copyOf(target));
        }
        data.put("observed_targets", List.copyOf(observedTargets));
        if (episodePolicyId != null) {
            data.put("combat_policy_id", episodePolicyId);
            data.put("combat_policy_ticks", episodePolicyTicks);
            data.put("combat_policy_success", policySuccess);
            data.put(
                    "combat_policy_safety_preempted",
                    episodePolicySafetyPreempted);
        }
        if (lastDecision != null) {
            data.put("decision",
                    lastDecision.action().name().toLowerCase(Locale.ROOT));
            data.put("risk",
                    lastDecision.risk().name().toLowerCase(Locale.ROOT));
            data.put("reasons", lastDecision.reasons().stream()
                    .map(reason -> reason.name().toLowerCase(Locale.ROOT))
                    .toList());
            data.put("hard_vetoes", lastDecision.hardVetoes().stream()
                    .map(veto -> veto.code().name().toLowerCase(Locale.ROOT))
                    .toList());
        }
        CompanionEventBus.publish(
                self,
                "defense_finished",
                self.getHealth() <= SurvivalDecisions.LOW_HEALTH
                        ? CompanionEventBus.PRIORITY_URGENT
                        : CompanionEventBus.PRIORITY_NORMAL,
                "The local survival episode ended after a confirmed quiet window.",
                data);
        if (bodyLog != null) {
            bodyLog.report(defeated > 0
                    ? "survived " + episodeThreatType + " and defeated "
                            + defeated + " threat(s)"
                    : "reached safety from " + episodeThreatType);
        }

        episodeActive = false;
        episodeStartPosition = null;
        episodeThreatType = null;
        maxEngagedThreats = 0;
        episodeEntities.clear();
        lastDecision = null;
        lastFocus = null;
        committedSafety = null;
        policySnapshot = null;
        policyCursor = null;
        policyTargetUuid = null;
        currentPolicyAction = null;
        episodePolicyId = null;
        episodePolicyTicks = 0;
        episodePolicyFailed = false;
        episodePolicySafetyPreempted = false;
        mode = Mode.NONE;
    }

    private void announceBody(NumenPlayer self) {
        if (bodyAnnounced) return;
        bodyAnnounced = true;
        Map<String, Object> data =
                new LinkedHashMap<>(CompanionEventBus.bodySnapshot(self));
        data.put("entity_id", self.getId());
        data.put("reason", "body_brain_attached");
        CompanionEventBus.publish(
                self,
                "body_available",
                CompanionEventBus.PRIORITY_NORMAL,
                "The companion body is live and its local survival director is attached.",
                data);
    }

    private void enterMode(NumenPlayer self, Mode next) {
        if (mode == next) return;
        stopNav();
        stopMelee();
        ranged.stop(self);
        stopGuard();
        stopDoorInteraction();
        consecutiveNavFails = 0;
        blindRetreat = false;
        mode = next;
    }

    private void releasePhysical(NumenPlayer self) {
        stopNav();
        stopMelee();
        ranged.stop(self);
        stopGuard();
        stopDoorInteraction();
        InputDriver.halt(self);
        self.setShiftKeyDown(false);
        mode = Mode.NONE;
    }

    private void stopNav() {
        if (nav != null) {
            nav.stop();
            nav = null;
        }
        navTargetId = -1;
    }

    private void stopMelee() {
        if (melee != null) {
            melee.stop();
            melee = null;
        }
        meleeTargetId = -1;
    }

    private void stopGuard() {
        if (guard != null) {
            guard.stop();
            guard = null;
        }
    }

    private void stopDoorInteraction() {
        if (doorInteraction != null) {
            doorInteraction.stop();
            doorInteraction = null;
        }
        doorInteractionGoal = null;
    }

    private boolean isShelterMode() {
        return mode == Mode.SHELTER_APPROACH
                || mode == Mode.SHELTER_CROSS
                || mode == Mode.SECURE_DOOR
                || mode == Mode.SHELTER_SETTLE;
    }

    private static boolean doorIsOpen(NumenPlayer self, BlockPos lower) {
        BlockState state = self.level().getBlockState(lower);
        return state.getBlock() instanceof DoorBlock
                && state.hasProperty(BlockStateProperties.OPEN)
                && state.getValue(BlockStateProperties.OPEN);
    }

    private static boolean isInside(LiveShelter shelter, BlockPos feet) {
        return shelter.blueprint().bounds().horizontallyInside(feet)
                && feet.getY() > shelter.blueprint().floorY()
                && feet.getY() < shelter.blueprint().roofY();
    }

    private static boolean inReach(NumenPlayer self, LivingEntity threat) {
        return self.distanceToSqr(Vec3.atCenterOf(threat.blockPosition()))
                <= ATTACK_REACH_SQR
                && self.hasLineOfSight(threat);
    }

    private static boolean focusGoneOrFar(
            NumenPlayer self, LivingEntity focus) {
        return focus == null
                || focus.isRemoved()
                || !focus.isAlive()
                || focus.level() != self.level()
                || self.distanceTo(focus) >= 12.0;
    }

    private static String entityType(LivingEntity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}
