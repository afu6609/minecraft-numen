package com.dwinovo.numen.core.combat.observe;

import com.dwinovo.numen.core.combat.observe.CombatObservationAdapter.Context;
import com.dwinovo.numen.core.combat.observe.CombatObservationAdapter.Result;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ordered adapter registry with conservative built-in vanilla observations.
 * Mods may register a more specific adapter without changing this package.
 */
public final class CombatObservationAdapters {

    private static final CombatObservationAdapters DEFAULTS = vanillaDefaults();

    private final CopyOnWriteArrayList<CombatObservationAdapter> adapters =
            new CopyOnWriteArrayList<>();

    public static CombatObservationAdapters defaults() {
        return DEFAULTS;
    }

    public CombatObservationAdapters register(CombatObservationAdapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("adapter is required");
        adapters.removeIf(existing -> existing.id().equals(adapter.id()));
        adapters.add(adapter);
        adapters.sort(Comparator.comparingInt(CombatObservationAdapter::priority).reversed());
        return this;
    }

    public boolean unregister(String id) {
        return adapters.removeIf(adapter -> adapter.id().equals(id));
    }

    public List<String> adapterIds() {
        return adapters.stream().map(CombatObservationAdapter::id).toList();
    }

    public CombatObservationAdapter select(LivingEntity entity) {
        return adapters.stream()
                .filter(adapter -> adapter.supports(entity))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no combat observation adapter for " + entity.getClass().getName()));
    }

    private static CombatObservationAdapters vanillaDefaults() {
        return new CombatObservationAdapters()
                .register(new CreeperAdapter())
                .register(new PhantomAdapter())
                .register(new SpiderAdapter())
                .register(new RangedAdapter())
                .register(new GenericMobAdapter())
                .register(new GenericLivingAdapter());
    }

    private static Result mobFallback(Context context, Map<String, Object> state,
                                      String specialEvidence) {
        CombatFacts facts = context.facts();
        List<String> evidence = new ArrayList<>();
        if (specialEvidence != null) evidence.add(specialEvidence);
        if (facts.targetIsObserver()) {
            evidence.add("server target is the observer");
            if (facts.distance() <= 3.2 && facts.lineOfSightToObserver()) {
                Vec3 towardObserver = context.observer().position()
                        .subtract(context.entity().position()).normalize();
                double closingSpeed =
                        context.entity().getDeltaMovement().dot(towardObserver);
                evidence.add("observer is nearby, but range alone does not prove a melee windup");
                if (closingSpeed > 0.04) {
                    evidence.add("entity velocity is closing on the observer");
                } else {
                    evidence.add("no server-confirmed attack animation or windup is exposed");
                }
                return new Result(
                        CombatObservationHeuristics.nearbyTarget(
                                closingSpeed, evidence),
                        state);
            }
            evidence.add(facts.lineOfSightToObserver()
                    ? "clear line of sight" : "line of sight is blocked");
            return result(CombatAction.APPROACH, CombatPhase.EXECUTING, 0.78,
                    null, null, evidence, state);
        }
        if (facts.targetEntityId() != null) {
            evidence.add("server target is another entity");
            return result(CombatAction.APPROACH, CombatPhase.OBSERVED, 0.72,
                    null, null, evidence, state);
        }
        return new Result(CombatIntent.idle("server reports no attack target"), state);
    }

    private static Result result(CombatAction action, CombatPhase phase, double confidence,
                                 Integer minTicks, Integer maxTicks, List<String> evidence,
                                 Map<String, Object> state) {
        return new Result(
                new CombatIntent(action, phase, confidence, minTicks, maxTicks, evidence),
                state);
    }

    private static final class CreeperAdapter implements CombatObservationAdapter {
        @Override public String id() { return "vanilla:creeper"; }
        @Override public int priority() { return 100; }
        @Override public boolean supports(LivingEntity entity) { return entity instanceof Creeper; }

        @Override
        public Result observe(Context context) {
            Creeper creeper = (Creeper) context.entity();
            Map<String, Object> state = new LinkedHashMap<>();
            int swellDirection = creeper.getSwellDir();
            float explosionProgress = creeper.getSwelling(1.0F);
            state.put("swell_direction", swellDirection);
            state.put("explosion_progress", explosionProgress);
            state.put("powered", creeper.isPowered());
            state.put("manually_ignited", creeper.isIgnited());
            if (swellDirection > 0 || creeper.isIgnited()) {
                int remaining = Math.max(0, Math.round((1.0F - explosionProgress) * 30.0F));
                return result(CombatAction.AOE_CHARGE, CombatPhase.WINDUP, 0.99,
                        Math.max(0, remaining - 2), remaining + 2,
                        List.of(
                                "creeper swell direction is positive",
                                "server explosion progress=" + explosionProgress),
                        state);
            }
            return mobFallback(context, state, "creeper is not currently swelling");
        }
    }

    private static final class PhantomAdapter implements CombatObservationAdapter {
        @Override public String id() { return "vanilla:phantom"; }
        @Override public int priority() { return 90; }
        @Override public boolean supports(LivingEntity entity) { return entity instanceof Phantom; }

        @Override
        public Result observe(Context context) {
            Vec3 towardObserver = context.observer().position()
                    .subtract(context.entity().position()).normalize();
            double closingSpeed = context.entity().getDeltaMovement().dot(towardObserver);
            Map<String, Object> state = Map.of(
                    "airborne", !context.entity().onGround(),
                    "closing_speed", closingSpeed);
            if (context.facts().targetIsObserver() && closingSpeed > 0.12) {
                return result(CombatAction.DASH, CombatPhase.EXECUTING, 0.9,
                        0, 20,
                        List.of(
                                "phantom targets the observer",
                                "phantom velocity is closing on the observer"),
                        state);
            }
            return mobFallback(context, state, "phantom is airborne");
        }
    }

    private static final class SpiderAdapter implements CombatObservationAdapter {
        @Override public String id() { return "vanilla:spider"; }
        @Override public int priority() { return 80; }
        @Override public boolean supports(LivingEntity entity) { return entity instanceof Spider; }

        @Override
        public Result observe(Context context) {
            Spider spider = (Spider) context.entity();
            Map<String, Object> state = Map.of("climbing", spider.isClimbing());
            String evidence = spider.isClimbing()
                    ? "spider is climbing; ordinary height separation is not safe"
                    : "spider is not currently climbing";
            return mobFallback(context, state, evidence);
        }
    }

    private static final class RangedAdapter implements CombatObservationAdapter {
        private static final double PROJECTILE_SCAN_RADIUS = 16.0;
        private final RangedObservationTracker tracker =
                new RangedObservationTracker();

        @Override public String id() { return "vanilla:ranged_attack_mob"; }
        @Override public int priority() { return 70; }
        @Override public boolean supports(LivingEntity entity) {
            return entity instanceof RangedAttackMob;
        }

        @Override
        public Result observe(Context context) {
            LivingEntity entity = context.entity();
            int useTicks = entity.getTicksUsingItem();
            List<RangedObservationTracker.ProjectileSample> projectiles =
                    ownedRecentProjectiles(entity);
            RangedObservationTracker.Window window = tracker.observe(
                    entity.getUUID(),
                    context.facts().serverTick(),
                    projectiles);
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("ranged_capable", true);
            state.put("item_use_ticks", useTicks);
            state.put("owned_recent_projectiles", projectiles.size());
            state.put("projectile_release_observed", window.projectileRelease());
            state.put("recovering_after_release", window.recovering());

            if (window.projectileRelease()) {
                return result(CombatAction.PROJECTILE_RELEASE, CombatPhase.EXECUTING, 0.99,
                        0, 1,
                        List.of(
                                "a newly-seen young projectile has this entity as server owner",
                                "projectile release is observed, not inferred from aim"),
                        state);
            }
            if (window.recovering()) {
                state.put(
                        "recovery_ticks_remaining",
                        window.recoveryTicksRemaining());
                return result(CombatAction.RECOVER, CombatPhase.RECOVERY, 0.92,
                        0, window.recoveryTicksRemaining(),
                        List.of(
                                "server recently observed a new owned projectile",
                                "short post-release recovery window is active"),
                        state);
            }

            ItemStack useItem = entity.getUseItem();
            boolean explicitWeaponUse = entity.isUsingItem()
                    && (useItem.getItem() instanceof BowItem
                    || useItem.getItem() instanceof CrossbowItem);
            if (context.facts().targetIsObserver() && explicitWeaponUse) {
                int remaining = Math.max(0, 20 - useTicks);
                return result(CombatAction.RANGED_CHARGE, CombatPhase.WINDUP, 0.86,
                        remaining, remaining + 6,
                        List.of(
                                "entity implements RangedAttackMob",
                                "server reports active bow/crossbow item use while targeting the observer"),
                        state);
            }
            if (context.facts().targetIsObserver()
                    && context.facts().lineOfSightToObserver()) {
                return result(CombatAction.UNKNOWN, CombatPhase.AIMING, 0.4,
                        null, null,
                        List.of(
                                "entity implements RangedAttackMob",
                                "observer is its target with clear line of sight",
                                "no explicit item-use telegraph or new owned projectile proves a shot phase"),
                        state);
            }
            return mobFallback(context, state, "entity implements RangedAttackMob");
        }

        private static List<RangedObservationTracker.ProjectileSample>
        ownedRecentProjectiles(LivingEntity shooter) {
            List<Projectile> found = shooter.level().getEntitiesOfClass(
                    Projectile.class,
                    shooter.getBoundingBox().inflate(PROJECTILE_SCAN_RADIUS),
                    projectile -> projectile.getOwner() == shooter
                            && projectile.tickCount
                            <= RangedObservationTracker.MAX_TRACKED_PROJECTILE_AGE_TICKS);
            List<RangedObservationTracker.ProjectileSample> samples =
                    new ArrayList<>(Math.min(
                            found.size(),
                            RangedObservationTracker.MAX_PROJECTILES_PER_SAMPLE));
            for (Projectile projectile : found) {
                if (samples.size()
                        >= RangedObservationTracker.MAX_PROJECTILES_PER_SAMPLE) {
                    break;
                }
                samples.add(new RangedObservationTracker.ProjectileSample(
                        projectile.getUUID(), projectile.tickCount));
            }
            return samples;
        }
    }

    private static final class GenericMobAdapter implements CombatObservationAdapter {
        @Override public String id() { return "vanilla:generic_mob"; }
        @Override public int priority() { return 10; }
        @Override public boolean supports(LivingEntity entity) { return entity instanceof Mob; }
        @Override public Result observe(Context context) {
            return mobFallback(context, Map.of(), null);
        }
    }

    private static final class GenericLivingAdapter implements CombatObservationAdapter {
        @Override public String id() { return "vanilla:generic_living"; }
        @Override public int priority() { return 0; }
        @Override public boolean supports(LivingEntity entity) { return true; }
        @Override public Result observe(Context context) {
            return result(CombatAction.UNKNOWN, CombatPhase.OBSERVED, 0.25,
                    null, null,
                    List.of("living entity exposes no supported combat controller"),
                    Map.of());
        }
    }
}
