package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.core.task.combat.CombatDecisionInput.TerrainState;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Small loaded-chunk-only terrain probe for combat. It deliberately returns
 * geometry facts and candidate feet cells; pathfinding remains authoritative
 * about whether a candidate can actually be reached.
 */
public final class CombatTerrainProbe {

    private static final int COVER_RADIUS = 5;
    private static final int ESCAPE_DISTANCE = 5;
    private static final int[][] DIRECTIONS = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1},
            {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    private CombatTerrainProbe() {}

    public record Result(
            TerrainState state,
            BlockPos escapeTarget,
            BlockPos hardCoverTarget) {}

    public static Result inspect(
            NumenPlayer self,
            List<? extends LivingEntity> threats,
            ShelterFacts shelter) {
        Level level = self.level();
        BlockPos origin = self.blockPosition();
        LivingEntity primary = threats.stream()
                .min(Comparator.comparingDouble(self::distanceToSqr))
                .orElse(null);

        int safeDirections = 0;
        List<BlockPos> escapeCandidates = new ArrayList<>();
        for (int[] direction : DIRECTIONS) {
            BlockPos near = findStandingAtSurface(
                    level, origin.offset(direction[0] * 2, 0, direction[1] * 2));
            if (near != null && loadedAndSafe(level, near)) {
                safeDirections++;
            }
            BlockPos far = findStandingAtSurface(
                    level,
                    origin.offset(
                            direction[0] * ESCAPE_DISTANCE,
                            0,
                            direction[1] * ESCAPE_DISTANCE));
            if (far != null
                    && loadedAndSafe(level, far)
                    && likelyWalkable(level, origin, far)
                    && !safeShelterBlocksEscape(shelter, far)) {
                escapeCandidates.add(far);
            }
        }

        BlockPos escape = escapeCandidates.stream()
                .max(Comparator.comparingDouble(pos -> separationScore(pos, threats)))
                .orElse(null);
        List<? extends LivingEntity> coverThreats = threats.stream()
                .filter(threat -> threat instanceof RangedAttackMob)
                .toList();
        if (coverThreats.isEmpty() && primary != null) {
            coverThreats = List.of(primary);
        }
        BlockPos cover = findHardCover(self, coverThreats);
        boolean overhead = hasProtectiveRoof(level, origin);
        int passableNeighbours = passableNeighbourCount(level, origin);
        boolean choke = passableNeighbours >= 1 && passableNeighbours <= 2;
        double coverDistance = cover == null
                ? 0.0 : Math.sqrt(origin.distSqr(cover));

        ShelterFacts safeShelter = shelter == null ? ShelterFacts.none() : shelter;
        TerrainState decisionState = new TerrainState(
                overhead,
                cover != null,
                coverDistance,
                safeDirections,
                safeDirections >= 5,
                choke,
                safeShelter.inside(),
                safeShelter.reachable(),
                safeShelter.distance(),
                safeShelter.enclosed(),
                safeShelter.spawnSafe(),
                safeShelter.entranceSecured());
        return new Result(decisionState, escape, cover);
    }

    /**
     * Narrow adapter seam used by the shelter subsystem. A remembered blueprint
     * is not safe unless the live enclosure and spawn-light checks both pass.
     */
    public record ShelterFacts(
            boolean inside,
            boolean reachable,
            double distance,
            boolean enclosed,
            boolean spawnSafe,
            boolean entranceSecured,
            BlockPos protectedMin,
            BlockPos protectedMax) {

        public ShelterFacts {
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException("distance must be finite and non-negative");
            }
        }

        public static ShelterFacts none() {
            return new ShelterFacts(
                    false, false, 0.0, false, false, false, null, null);
        }
    }

    private static boolean safeShelterBlocksEscape(
            ShelterFacts shelter, BlockPos candidate) {
        if (shelter == null
                || shelter.inside()
                || shelter.protectedMin() == null
                || shelter.protectedMax() == null) {
            return false;
        }
        int margin = 2;
        return candidate.getX() >= shelter.protectedMin().getX() - margin
                && candidate.getX() <= shelter.protectedMax().getX() + margin
                && candidate.getY() >= shelter.protectedMin().getY() - margin
                && candidate.getY() <= shelter.protectedMax().getY() + margin
                && candidate.getZ() >= shelter.protectedMin().getZ() - margin
                && candidate.getZ() <= shelter.protectedMax().getZ() + margin;
    }

    private static BlockPos findHardCover(
            NumenPlayer self, List<? extends LivingEntity> threats) {
        if (threats.isEmpty()) return null;
        Level level = self.level();
        BlockPos origin = self.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -COVER_RADIUS; dx <= COVER_RADIUS; dx++) {
            for (int dz = -COVER_RADIUS; dz <= COVER_RADIUS; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos candidate = findStandingAtSurface(level, origin.offset(dx, 0, dz));
                if (candidate == null
                        || !loadedAndSafe(level, candidate)
                        || !likelyWalkable(level, origin, candidate)) {
                    continue;
                }
                Vec3 candidateEyes = Vec3.atBottomCenterOf(candidate).add(0.0, 1.62, 0.0);
                if (threats.stream().allMatch(threat ->
                        blockedBetween(
                                level,
                                threat.getEyePosition(),
                                candidateEyes,
                                self))) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates.stream()
                .min(Comparator.comparingDouble(origin::distSqr))
                .orElse(null);
    }

    private static boolean blockedBetween(
            Level level, Vec3 from, Vec3 to, NumenPlayer self) {
        BlockHitResult hit = level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getLocation().distanceToSqr(from) + 0.25
                < to.distanceToSqr(from);
    }

    private static int passableNeighbourCount(Level level, BlockPos origin) {
        int count = 0;
        for (int[] direction : DIRECTIONS) {
            if (Math.abs(direction[0]) + Math.abs(direction[1]) != 1) continue;
            BlockPos candidate = findStandingAtSurface(
                    level, origin.offset(direction[0], 0, direction[1]));
            if (candidate != null && loadedAndSafe(level, candidate)) count++;
        }
        return count;
    }

    /**
     * Cheap loaded-only route precheck. It is not a replacement for PlayerNav,
     * but filters candidates whose straight approach obviously crosses a
     * two-block ledge, unloaded cell, liquid, or local hazard.
     */
    private static boolean likelyWalkable(Level level, BlockPos from, BlockPos to) {
        int steps = Math.max(
                Math.abs(to.getX() - from.getX()),
                Math.abs(to.getZ() - from.getZ()));
        if (steps == 0) return true;
        BlockPos previous = from;
        for (int index = 1; index <= steps; index++) {
            double progress = index / (double) steps;
            int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * progress);
            int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * progress);
            BlockPos sample = findStandingAtSurface(
                    level, new BlockPos(x, previous.getY(), z));
            if (sample == null || Math.abs(sample.getY() - previous.getY()) > 1) {
                return false;
            }
            previous = sample;
        }
        return true;
    }

    private static double separationScore(
            BlockPos candidate, List<? extends LivingEntity> threats) {
        if (threats.isEmpty()) return 0.0;
        return threats.stream()
                .mapToDouble(threat -> candidate.distToCenterSqr(
                        threat.getX(), threat.getY(), threat.getZ()))
                .min()
                .orElse(0.0);
    }

    private static BlockPos findStandingAtSurface(Level level, BlockPos requested) {
        for (int dy : new int[]{0, 1, -1, 2, -2}) {
            BlockPos feet = requested.offset(0, dy, 0);
            if (loadedAndSafe(level, feet)) return feet.immutable();
        }
        return null;
    }

    private static boolean loadedAndSafe(Level level, BlockPos feet) {
        if (!level.hasChunkAt(feet)) return false;
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(feet.above());
        BlockState floorState = level.getBlockState(feet.below());
        return feetState.getCollisionShape(level, feet).isEmpty()
                && headState.getCollisionShape(level, feet.above()).isEmpty()
                && !floorState.getCollisionShape(level, feet.below()).isEmpty()
                && !isHazard(feetState)
                && !isHazard(headState)
                && !isHazard(floorState);
    }

    /**
     * A single branch, fence, or isolated block is not reliable phantom cover.
     * Require a continuous 3x3 downward-sturdy roof at a normal interior
     * ceiling height so HOLD_SAFE_POSITION cannot latch onto incidental scenery.
     */
    private static boolean hasProtectiveRoof(Level level, BlockPos feet) {
        for (int dy = 2; dy <= 4; dy++) {
            boolean continuous = true;
            for (int dx = -1; dx <= 1 && continuous; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos roof = feet.offset(dx, dy, dz);
                    if (!level.hasChunkAt(roof)
                            || !level.getBlockState(roof)
                                    .isFaceSturdy(level, roof, Direction.DOWN)) {
                        continuous = false;
                        break;
                    }
                }
            }
            if (continuous) return true;
        }
        return false;
    }

    private static boolean isHazard(BlockState state) {
        if (state.getFluidState().is(FluidTags.LAVA)) return true;
        if (state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH)) {
            return true;
        }
        return CampfireBlock.isLitCampfire(state);
    }
}
