package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.core.task.combat.CombatTerrainProbe.ShelterFacts;
import com.dwinovo.numen.core.task.survival.shelter.MinecraftShelterWorldSampler;
import com.dwinovo.numen.core.task.survival.shelter.ShelterAnalysis;
import com.dwinovo.numen.core.task.survival.shelter.ShelterAnalyzer;
import com.dwinovo.numen.core.task.survival.shelter.ShelterBlueprint;
import com.dwinovo.numen.core.task.survival.shelter.ShelterVerification;
import com.dwinovo.numen.core.task.survival.shelter.ShelterVerifier;
import com.dwinovo.numen.core.tools.StructureWorkflowSnapshots;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Resolves and live-certifies recent companion-owned structures as shelter. */
public final class CombatShelterResolver {

    private static final double MAX_SHELTER_ROUTE_DISTANCE = 64.0;
    private static final int MAX_WORKFLOW_CANDIDATES = 16;
    private static final int MAX_LEGACY_CANDIDATES = 16;
    private static final int BLUEPRINT_CACHE_LIMIT = 64;
    private static final Map<BlueprintCacheKey, ShelterAnalysis>
            BLUEPRINT_CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<BlueprintCacheKey, ShelterAnalysis> eldest) {
                    return size() > BLUEPRINT_CACHE_LIMIT;
                }
            };

    private CombatShelterResolver() {}

    public record LiveShelter(
            ShelterBlueprint blueprint,
            ShelterVerification verification,
            BlockPos stance,
            ShelterBlueprint.Doorway doorway,
            ShelterFacts facts,
            boolean hostileInside,
            boolean doorwayClear) {}

    private record BlueprintCacheKey(
            String ownerUuid,
            String workflowId,
            long revision,
            String dimensionId) {}

    private record StaticShelter(
            ShelterBlueprint blueprint,
            double distanceSqr) {}

    public static Optional<LiveShelter> latest(NumenPlayer self) {
        List<StructureWorkflowSnapshots.WorkflowSnapshot> workflows;
        try {
            workflows = StructureWorkflowSnapshots.owned(
                    self,
                    MAX_WORKFLOW_CANDIDATES,
                    MAX_LEGACY_CANDIDATES);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            return Optional.empty();
        }
        String currentDimension =
                self.level().dimension().location().toString();
        MinecraftShelterWorldSampler sampler =
                new MinecraftShelterWorldSampler(self.level());
        List<StaticShelter> candidates = new ArrayList<>(workflows.size());

        for (StructureWorkflowSnapshots.WorkflowSnapshot saved : workflows) {
            try {
                StructureWorkflowSnapshots.WorkflowSnapshot workflow =
                        prepareCandidate(self, saved, currentDimension);
                if (workflow == null
                        || workflow.dimensionId().isEmpty()
                        || !currentDimension.equals(
                        workflow.dimensionId().orElseThrow())) {
                    continue;
                }

                ShelterAnalysis analysis = analyzeCached(workflow);
                if (analysis.outcome()
                        != ShelterAnalysis.Outcome.SUPPORTED_RECTANGULAR
                        || analysis.blueprint().isEmpty()) {
                    continue;
                }
                ShelterBlueprint blueprint =
                        analysis.blueprint().orElseThrow();
                // V1 deliberately supports exactly one controllable entrance.
                if (blueprint.doors().size() != 1) {
                    continue;
                }
                candidates.add(new StaticShelter(
                        blueprint,
                        nearestStanceDistanceSqr(self, blueprint)));
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // One stale/corrupt workflow must not hide other recent houses.
            }
        }
        candidates.sort(Comparator.comparingDouble(
                StaticShelter::distanceSqr));
        // Live verification is intentionally nearest-first and stops at the
        // first valid shelter. Static recognition above is revision-cached, so
        // the normal steady-state path reads only one house from the world.
        for (StaticShelter candidate : candidates) {
            LiveShelter live =
                    certify(self, candidate.blueprint(), sampler);
            if (live != null) {
                return Optional.of(live);
            }
        }
        return Optional.empty();
    }

    /**
     * Statically screen a legacy candidate as a single-door rectangular shelter,
     * then claim the current dimension only through the store's exact live-match
     * transaction.
     */
    private static StructureWorkflowSnapshots.WorkflowSnapshot prepareCandidate(
            NumenPlayer self,
            StructureWorkflowSnapshots.WorkflowSnapshot workflow,
            String currentDimension) {
        if (workflow.dimensionId().isPresent()) {
            return currentDimension.equals(
                    workflow.dimensionId().orElseThrow())
                    ? workflow : null;
        }
        StructureWorkflowSnapshots.WorkflowSnapshot provisional =
                withDimension(workflow, currentDimension);
        ShelterAnalysis staticAnalysis = analyzeCached(provisional);
        if (staticAnalysis.outcome()
                != ShelterAnalysis.Outcome.SUPPORTED_RECTANGULAR
                || staticAnalysis.blueprint().isEmpty()
                || staticAnalysis.blueprint().orElseThrow()
                .doors().size() != 1) {
            return null;
        }
        StructureWorkflowSnapshots.DimensionClaimResult claim =
                StructureWorkflowSnapshots.claimLegacyDimensionIfExact(
                        self, workflow.id());
        if (!claim.usable() || claim.snapshot().isEmpty()) {
            return null;
        }
        StructureWorkflowSnapshots.WorkflowSnapshot claimed =
                claim.snapshot().orElseThrow();
        return claimed.dimensionId().filter(currentDimension::equals)
                .map(ignored -> claimed)
                .orElse(null);
    }

    private static ShelterAnalysis analyzeCached(
            StructureWorkflowSnapshots.WorkflowSnapshot workflow) {
        BlueprintCacheKey key = new BlueprintCacheKey(
                workflow.ownerUuid(),
                workflow.id(),
                workflow.revision(),
                workflow.dimensionId().orElse(""));
        synchronized (BLUEPRINT_CACHE) {
            ShelterAnalysis cached = BLUEPRINT_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        ShelterAnalysis analysis = ShelterAnalyzer.analyze(workflow);
        synchronized (BLUEPRINT_CACHE) {
            BLUEPRINT_CACHE.put(key, analysis);
        }
        return analysis;
    }

    private static double nearestStanceDistanceSqr(
            NumenPlayer self, ShelterBlueprint blueprint) {
        double nearest = Double.POSITIVE_INFINITY;
        for (BlockPos stance : blueprint.interiorStances()) {
            nearest = Math.min(
                    nearest,
                    self.blockPosition().distSqr(stance));
        }
        return nearest;
    }

    private static StructureWorkflowSnapshots.WorkflowSnapshot withDimension(
            StructureWorkflowSnapshots.WorkflowSnapshot workflow,
            String dimensionId) {
        return new StructureWorkflowSnapshots.WorkflowSnapshot(
                workflow.id(),
                workflow.ownerUuid(),
                workflow.ownerName(),
                Optional.of(dimensionId),
                workflow.name(),
                workflow.goal(),
                workflow.allowReplace(),
                workflow.createdAt(),
                workflow.updatedAt(),
                workflow.revision(),
                workflow.lastOperation(),
                workflow.cells());
    }

    /** Live-certify one static single-door blueprint, allowing only its open door as a breach. */
    private static LiveShelter certify(
            NumenPlayer self,
            ShelterBlueprint blueprint,
            MinecraftShelterWorldSampler sampler) {
        ShelterVerification verification =
                ShelterVerifier.verify(blueprint, sampler);
        if (verification.status()
                == ShelterVerification.Status.WRONG_DIMENSION
                || verification.status()
                == ShelterVerification.Status.INCOMPLETE_UNLOADED
                || verification.status()
                == ShelterVerification.Status.LIMIT_EXCEEDED) {
            return null;
        }
        Optional<BlockPos> stance = verification.preferredSafeStance();
        if (stance.isEmpty()
                || verification.doors().size() != 1
                || blueprint.doors().size() != 1) {
            return null;
        }

        ShelterBlueprint.Doorway door = blueprint.doors().get(0);
        ShelterVerification.DoorStatus doorStatus =
                verification.doors().get(0);
        boolean doorsPresent =
                doorStatus.loaded() && doorStatus.present();
        boolean entranceSecured =
                doorsPresent && !doorStatus.open();
        int explainedOpenDoorBreaches =
                doorStatus.open() ? 2 : 0;
        int unexplainedBreaches = Math.max(
                0,
                verification.breachedShellCells()
                        - explainedOpenDoorBreaches);
        boolean enclosed = verification.unloadedSamples() == 0
                && verification.mismatchedBlueprintCells() == 0
                && unexplainedBreaches == 0
                && doorsPresent;
        boolean spawnSafe = verification.darkStanceCount() == 0
                && verification.minimumBlockLight()
                >= ShelterVerifier.MIN_SAFE_BLOCK_LIGHT;
        boolean inside = isInside(blueprint, self.blockPosition());
        double distance = Math.sqrt(
                self.blockPosition().distSqr(stance.orElseThrow()));
        boolean hostileInside = hasHostileInside(self, blueprint);
        boolean doorwayClear = doorwayIsClear(self, door);
        boolean reachable =
                distance <= MAX_SHELTER_ROUTE_DISTANCE
                        && self.level().hasChunkAt(stance.orElseThrow())
                        && doorwayClear;
        if (!enclosed || !spawnSafe || (!inside && !reachable)) {
            return null;
        }

        ShelterFacts facts = new ShelterFacts(
                inside,
                reachable,
                distance,
                enclosed,
                spawnSafe,
                entranceSecured,
                new BlockPos(
                        blueprint.bounds().minX(),
                        blueprint.bounds().minY(),
                        blueprint.bounds().minZ()),
                new BlockPos(
                        blueprint.bounds().maxX(),
                        blueprint.bounds().maxY(),
                        blueprint.bounds().maxZ()));
        return new LiveShelter(
                blueprint,
                verification,
                stance.orElseThrow(),
                door,
                facts,
                hostileInside,
                doorwayClear);
    }

    private static boolean isInside(ShelterBlueprint blueprint, BlockPos feet) {
        return blueprint.bounds().horizontallyInside(feet)
                && feet.getY() > blueprint.floorY()
                && feet.getY() < blueprint.roofY();
    }

    private static boolean hasHostileInside(
            NumenPlayer self, ShelterBlueprint blueprint) {
        ShelterBlueprint.Bounds bounds = blueprint.bounds();
        AABB interior = new AABB(
                bounds.minX() + 1,
                blueprint.floorY() + 1,
                bounds.minZ() + 1,
                bounds.maxX(),
                blueprint.roofY(),
                bounds.maxZ());
        return !self.level().getEntitiesOfClass(
                LivingEntity.class,
                interior,
                entity -> entity != self
                        && entity.isAlive()
                        && (entity instanceof Enemy
                        || entity instanceof Mob mob && mob.getTarget() == self))
                .isEmpty();
    }

    private static boolean doorwayIsClear(
            NumenPlayer self, ShelterBlueprint.Doorway door) {
        BlockPos lower = door.lower();
        AABB threshold = new AABB(
                Math.min(door.inside().getX(), door.outside().getX()) - 0.35,
                lower.getY(),
                Math.min(door.inside().getZ(), door.outside().getZ()) - 0.35,
                Math.max(door.inside().getX(), door.outside().getX()) + 1.35,
                lower.getY() + 2.2,
                Math.max(door.inside().getZ(), door.outside().getZ()) + 1.35);
        boolean occupied = !self.level().getEntitiesOfClass(
                LivingEntity.class,
                threshold,
                entity -> entity != self && entity.isAlive()).isEmpty();
        if (occupied) return false;

        AABB danger = new AABB(lower).inflate(3.0);
        return self.level().getEntitiesOfClass(
                LivingEntity.class,
                danger,
                entity -> entity != self
                        && entity.isAlive()
                        && (entity instanceof Enemy
                        || entity instanceof Mob mob && mob.getTarget() == self))
                .isEmpty();
    }
}
