package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.event.CompanionEventBus;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.SurvivalConfig;
import com.dwinovo.numen.core.task.base.ToolSelect;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions.ThreatResponse;
import com.dwinovo.numen.core.task.survival.ThreatMemory;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskChain;
import com.google.common.collect.Multimap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immediate, local self-defense. The chain does not wait for a model turn: it
 * recognizes mobs actively targeting the body plus the authoritative recent
 * damage source, then fights a safe engagement or opens distance.
 *
 * <p>One compact episode is emitted to {@link CompanionEventBus}. Individual
 * hits are already captured by {@link ThreatMemory}; the external brain is
 * normally woken only after the local episode ends, preventing both latency and
 * one expensive reasoning turn per arrow.
 */
public final class MobDefenseChain implements TaskChain, com.dwinovo.numen.task.reflex.Reflex {

    /** Targeting mobs are relevant well beyond melee range (skeletons commonly shoot from 16+ blocks). */
    private static final double SCAN_RADIUS = 32.0;
    /** A freshly recorded attacker may be just outside the regular scan after knockback or retreat. */
    private static final double RECENT_ATTACKER_RADIUS = 40.0;
    private static final double ATTACK_REACH = 3.0;
    private static final double ATTACK_REACH_SQR = ATTACK_REACH * ATTACK_REACH;
    /** Enough room for one food use; priority rises immediately if the mob closes this gap. */
    private static final double SAFE_MEAL_DISTANCE_SQR = 12.0 * 12.0;
    private static final long QUIET_BEFORE_MEAL_TICKS = 30;
    private static final double CHASE_SPEED = 1.2;
    private static final double FLEE_SPEED = 1.3;

    private static final int MAX_ENGAGE_FAILS = 3;
    private static final long UNREACHABLE_COOLDOWN = 200;
    private static final long CHAIN_COOLDOWN = 100;

    private enum Mode { NONE, CHASE, FLEE }

    private record ThreatContext(
            LivingEntity target,
            int engagedCount,
            boolean intrinsicallyDangerous,
            boolean ranged,
            double distanceSqr) {}

    private final com.dwinovo.numen.task.BodyLog bodyLog;
    private final Map<Integer, Long> unreachable = new HashMap<>();

    private Mode mode = Mode.NONE;
    private Mode lastEpisodeMode = Mode.NONE;
    private LivingEntity target;
    private PlayerNav nav;
    private BlockPos lastThreatPos;
    private int consecutiveNavFails;
    private long cooldownUntilGameTime;
    private boolean bodyAnnounced;

    private boolean episodeActive;
    private Vec3 episodeStartPosition;
    private float episodeStartHealth;
    private long episodeStartGameTime;
    private String episodeThreatType;
    private int maxEngagedThreats;
    private int defeatedThreats;
    private final java.util.Set<Integer> countedDefeats = new java.util.HashSet<>();
    private String pendingOutcome;

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

        ThreatContext threat = threatContext(companion);
        if (episodeActive && threat == null) {
            finishEpisode(companion, pendingOutcome != null
                    ? pendingOutcome
                    : naturalOutcome());
        }
        if (companion.level().getGameTime() < cooldownUntilGameTime) {
            return Float.NEGATIVE_INFINITY;
        }
        if (threat == null) {
            return SurvivalDecisions.DORMANT;
        }
        return SurvivalDecisions.mobDefensePriority(
                true, shouldYieldForHealingFood(companion, threat));
    }

    @Override
    public void tick(NumenPlayer companion) {
        ThreatContext context = threatContext(companion);
        if (context == null) {
            releasePhysical(companion);
            finishEpisode(companion, pendingOutcome != null
                    ? pendingOutcome
                    : naturalOutcome());
            return;
        }
        startEpisode(companion, context);
        maxEngagedThreats = Math.max(maxEngagedThreats, context.engagedCount());

        LivingEntity threat = context.target();
        if (threat != target) {
            countDefeat(target);
            target = threat;
            consecutiveNavFails = 0;
            stopNav();
        }
        lastThreatPos = threat.blockPosition();

        boolean forceFlee = context.intrinsicallyDangerous()
                || context.ranged()
                || (context.engagedCount() >= 2 && companion.getHealth() <= 16.0f);
        ThreatResponse response = SurvivalDecisions.decideThreatResponse(
                true,
                companion.getHealth(),
                hasWeapon(companion),
                forceFlee,
                context.engagedCount());
        if (response == ThreatResponse.FIGHT) {
            fight(companion, threat);
        } else {
            flee(companion);
        }
    }

    @Override
    public void onInterrupt(NumenPlayer companion) {
        stopNav();
        InputDriver.halt(companion);
        companion.setShiftKeyDown(false);
        mode = Mode.NONE;
        ThreatContext stillEngaged = threatContext(companion);
        if (stillEngaged == null && episodeActive) {
            finishEpisode(companion, pendingOutcome != null
                    ? pendingOutcome
                    : naturalOutcome());
        }
    }

    @Override
    public String name() {
        return "mob_defense";
    }

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "受击或被怪物锁定时会即时反击或拉开距离，危险时先保命再吃东西";
    }

    private void fight(NumenPlayer companion, LivingEntity threat) {
        if (mode != Mode.CHASE) {
            stopNav();
            mode = Mode.CHASE;
            lastEpisodeMode = mode;
        }
        ToolSelect.holdBestWeapon(companion);
        if (inReach(companion, threat)) {
            stopNav();
            consecutiveNavFails = 0;
            Interaction.attackEntity(companion, threat).tick();
            return;
        }
        if (nav == null) {
            nav = new PlayerNav(companion, threat::blockPosition, CHASE_SPEED,
                    () -> inReach(companion, threat));
        }
        switch (nav.tick()) {
            case RUNNING, ARRIVED -> { /* close distance */ }
            case FAILED -> {
                stopNav();
                if (++consecutiveNavFails >= MAX_ENGAGE_FAILS) {
                    unreachable.put(threat.getId(),
                            companion.level().getGameTime() + UNREACHABLE_COOLDOWN);
                    consecutiveNavFails = 0;
                    pendingOutcome = "target_unreachable";
                    target = null;
                }
            }
        }
    }

    private void flee(NumenPlayer companion) {
        if (mode != Mode.FLEE) {
            stopNav();
            mode = Mode.FLEE;
            lastEpisodeMode = mode;
        }
        if (nav == null) {
            int maintainY = companion.blockPosition().getY();
            nav = PlayerNav.toGoal(companion,
                    () -> NavGoal.runAway(lastThreatPos, maintainY),
                    FLEE_SPEED,
                    () -> false);
        }
        if (nav.tick() == PlayerNav.Status.FAILED) {
            stopNav();
            if (++consecutiveNavFails >= MAX_ENGAGE_FAILS) {
                cooldownUntilGameTime = companion.level().getGameTime() + CHAIN_COOLDOWN;
                consecutiveNavFails = 0;
                releasePhysical(companion);
                finishEpisode(companion, "escape_path_failed");
            }
        }
    }

    private ThreatContext threatContext(NumenPlayer companion) {
        long now = companion.level().getGameTime();
        unreachable.entrySet().removeIf(entry -> entry.getValue() <= now);

        LivingEntity recentAttacker = ThreatMemory.resolveRecentAttacker(companion);
        Map<Integer, LivingEntity> engaged = new LinkedHashMap<>();
        AABB scan = companion.getBoundingBox().inflate(SCAN_RADIUS);
        for (Mob mob : companion.level().getEntitiesOfClass(Mob.class, scan)) {
            if (mob.isRemoved() || mob.isDeadOrDying()) continue;
            if (mob.getTarget() != companion && mob != recentAttacker) continue;
            engaged.put(mob.getId(), mob);
        }
        if (recentAttacker != null
                && companion.distanceToSqr(recentAttacker)
                <= RECENT_ATTACKER_RADIUS * RECENT_ATTACKER_RADIUS) {
            engaged.put(recentAttacker.getId(), recentAttacker);
        }

        LivingEntity best = null;
        double bestWeightedDistance = Double.MAX_VALUE;
        int actionable = 0;
        for (LivingEntity candidate : engaged.values()) {
            if (unreachable.getOrDefault(candidate.getId(), 0L) > now) {
                continue;
            }
            actionable++;
            double distance = companion.distanceToSqr(candidate);
            double weighted = candidate == recentAttacker ? distance - 4.0 : distance;
            if (weighted < bestWeightedDistance) {
                best = candidate;
                bestWeightedDistance = weighted;
            }
        }
        if (best == null) {
            return null;
        }
        boolean dangerous = best instanceof Creeper
                || best instanceof Player
                || best instanceof IronGolem;
        boolean ranged = best instanceof RangedAttackMob || best instanceof Phantom;
        return new ThreatContext(
                best,
                Math.max(1, actionable),
                dangerous,
                ranged,
                companion.distanceToSqr(best));
    }

    private boolean shouldYieldForHealingFood(NumenPlayer companion, ThreatContext context) {
        if (companion.getHealth() > SurvivalDecisions.LOW_HEALTH
                || companion.getFoodData().getFoodLevel() >= SurvivalDecisions.REGEN_FOOD_LEVEL
                || !FoodChain.hasEdible(companion)
                || context.ranged()
                || context.engagedCount() != 1
                || context.distanceSqr() < SAFE_MEAL_DISTANCE_SQR) {
            return false;
        }
        ThreatMemory.DamageSnapshot lastDamage = ThreatMemory.recent(companion);
        return lastDamage == null
                || companion.level().getGameTime() - lastDamage.gameTime()
                >= QUIET_BEFORE_MEAL_TICKS;
    }

    private void announceBody(NumenPlayer companion) {
        if (bodyAnnounced) return;
        bodyAnnounced = true;
        Map<String, Object> data =
                new LinkedHashMap<>(CompanionEventBus.bodySnapshot(companion));
        data.put("entity_id", companion.getId());
        data.put("reason", "body_brain_attached");
        CompanionEventBus.publish(
                companion,
                "body_available",
                CompanionEventBus.PRIORITY_NORMAL,
                "The companion body is live and its local reflexes are attached.",
                data);
    }

    private void startEpisode(NumenPlayer companion, ThreatContext context) {
        if (episodeActive) return;
        episodeActive = true;
        episodeStartPosition = companion.position();
        episodeStartHealth = companion.getHealth();
        episodeStartGameTime = companion.level().getGameTime();
        episodeThreatType = entityType(context.target());
        maxEngagedThreats = context.engagedCount();
        defeatedThreats = 0;
        countedDefeats.clear();
        pendingOutcome = null;
        lastEpisodeMode = Mode.NONE;

        Map<String, Object> data =
                new LinkedHashMap<>(CompanionEventBus.bodySnapshot(companion));
        data.put("threat_type", episodeThreatType);
        data.put("threat_entity_id", context.target().getId());
        data.put("threat_position", List.of(
                context.target().getX(),
                context.target().getY(),
                context.target().getZ()));
        data.put("engaged_threats", context.engagedCount());
        data.put("ranged", context.ranged());
        data.put("forced_retreat",
                context.intrinsicallyDangerous() || context.ranged());
        CompanionEventBus.publish(
                companion,
                "defense_started",
                CompanionEventBus.PRIORITY_URGENT,
                "Local self-defense took control of the body.",
                data);
    }

    private void finishEpisode(NumenPlayer companion, String outcome) {
        if (!episodeActive) return;
        countDefeat(target);
        Vec3 end = companion.position();
        double displaced = episodeStartPosition == null
                ? 0.0
                : episodeStartPosition.distanceTo(end);
        Map<String, Object> data =
                new LinkedHashMap<>(CompanionEventBus.bodySnapshot(companion));
        if (episodeStartPosition != null) {
            data.put("start_position", List.of(
                    episodeStartPosition.x,
                    episodeStartPosition.y,
                    episodeStartPosition.z));
        }
        data.put("health_before", episodeStartHealth);
        data.put("health_after", companion.getHealth());
        data.put("displaced_distance", displaced);
        data.put("duration_ticks",
                Math.max(0L, companion.level().getGameTime() - episodeStartGameTime));
        data.put("outcome", outcome);
        data.put("threat_type", episodeThreatType);
        data.put("max_engaged_threats", maxEngagedThreats);
        data.put("defeated_threats", defeatedThreats);
        data.put("response", lastEpisodeMode.name().toLowerCase(java.util.Locale.ROOT));
        int priority = companion.getHealth() <= SurvivalDecisions.LOW_HEALTH
                || "escape_path_failed".equals(outcome)
                ? CompanionEventBus.PRIORITY_URGENT
                : CompanionEventBus.PRIORITY_NORMAL;
        CompanionEventBus.publish(
                companion,
                "defense_finished",
                priority,
                "Local self-defense ended with outcome: " + outcome + ".",
                data);

        if (bodyLog != null) {
            if (defeatedThreats > 0) {
                bodyLog.report("defended against " + episodeThreatType
                        + " and defeated " + defeatedThreats + " threat(s)");
            } else if (lastEpisodeMode == Mode.FLEE) {
                bodyLog.report("escaped from " + episodeThreatType);
            }
        }

        episodeActive = false;
        episodeStartPosition = null;
        episodeThreatType = null;
        maxEngagedThreats = 0;
        defeatedThreats = 0;
        countedDefeats.clear();
        pendingOutcome = null;
        target = null;
        mode = Mode.NONE;
        lastEpisodeMode = Mode.NONE;
    }

    private String naturalOutcome() {
        if (defeatedThreats > 0 || (target != null
                && (target.isRemoved() || target.isDeadOrDying()))) {
            return "secured";
        }
        return lastEpisodeMode == Mode.FLEE ? "escaped" : "threat_cleared";
    }

    private void countDefeat(LivingEntity candidate) {
        if (candidate != null
                && (candidate.isDeadOrDying() || candidate.isRemoved())
                && countedDefeats.add(candidate.getId())) {
            defeatedThreats++;
        }
    }

    private static String entityType(LivingEntity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static boolean hasWeapon(NumenPlayer companion) {
        var inventory = companion.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (stackAttackBonus(inventory.getItem(i)) > 0.0) return true;
        }
        return false;
    }

    private static double stackAttackBonus(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;
        Multimap<Attribute, AttributeModifier> modifiers =
                stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
        double sum = 0.0;
        for (AttributeModifier modifier : modifiers.get(Attributes.ATTACK_DAMAGE)) {
            if (modifier.getOperation() == AttributeModifier.Operation.ADDITION) {
                sum += modifier.getAmount();
            }
        }
        return sum;
    }

    private static boolean inReach(NumenPlayer companion, LivingEntity threat) {
        return companion.distanceToSqr(Vec3.atCenterOf(threat.blockPosition()))
                <= ATTACK_REACH_SQR
                && companion.hasLineOfSight(threat);
    }

    private void releasePhysical(NumenPlayer companion) {
        stopNav();
        InputDriver.halt(companion);
        companion.setShiftKeyDown(false);
        mode = Mode.NONE;
    }

    private void stopNav() {
        if (nav != null) {
            nav.stop();
            nav = null;
        }
    }
}
