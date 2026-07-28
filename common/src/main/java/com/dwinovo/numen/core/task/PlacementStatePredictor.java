package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Vanilla block-state prediction shared by live building and read-only placement
 * feasibility checks.
 *
 * <p>The only temporary mutation is the player's yaw/pitch while vanilla evaluates
 * {@link BlockItem#getStateForPlacement}; both values are restored in {@code finally}.
 * The world and inventory are never changed.
 */
public final class PlacementStatePredictor {

    private static final float[] PLACEMENT_YAWS = {0.0f, 90.0f, 180.0f, -90.0f};
    private static final float[] PLACEMENT_PITCHES = {-75.0f, 0.0f, 75.0f};
    private static final double[] PLACEMENT_FACE_SAMPLES = {0.25, 0.5, 0.75};

    private PlacementStatePredictor() {}

    /**
     * Predict the state vanilla would create for one click, or {@code null} when
     * the item/context cannot place. Supplying yaw and pitch previews the rotation
     * selected by the ray resolver; null values preserve the current rotation.
     */
    public static BlockState predict(
            NumenPlayer player,
            ItemStack stack,
            BlockHitResult hit,
            Float yaw,
            Float pitch) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        try {
            if (yaw != null && pitch != null) {
                player.setYRot(yaw);
                player.setXRot(pitch);
            }
            BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(
                    player.level(), player, InteractionHand.MAIN_HAND, stack, hit) {});
            BlockState state = blockItem.getBlock().getStateForPlacement(context);
            return state != null && context.canPlace() ? state : null;
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
    }

    /**
     * Cheap, geometry-free proof that some vanilla placement context can create
     * the requested state. Live reach and line of sight are deliberately left to
     * {@code Placement}; this only rejects states the item can never emit.
     */
    public static boolean canCreateDesiredState(
            NumenPlayer player,
            BuildTaskRecord.Target target,
            ItemStack stack) {
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        try {
            for (float yaw : PLACEMENT_YAWS) {
                for (float pitch : PLACEMENT_PITCHES) {
                    player.setYRot(yaw);
                    player.setXRot(pitch);
                    for (Direction support : Direction.values()) {
                        for (double sample : PLACEMENT_FACE_SAMPLES) {
                            BlockState placed = predict(
                                    player, stack, syntheticHit(target.pos(), support, sample),
                                    null, null);
                            if (placed != null && target.acceptsPlacedState(placed)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
    }

    /** A support-face hit used only for the geometry-free producibility scan. */
    public static BlockHitResult syntheticHit(
            BlockPos placeAt, Direction support, double sample) {
        BlockPos against = placeAt.relative(support);
        double x = (placeAt.getX() + against.getX() + 1.0) * 0.5;
        double y = (placeAt.getY() + against.getY() + 1.0) * 0.5;
        double z = (placeAt.getZ() + against.getZ() + 1.0) * 0.5;
        if (support.getAxis().isHorizontal()) {
            y = placeAt.getY() + sample;
        }
        return new BlockHitResult(
                new Vec3(x, y, z), support.getOpposite(), against, false);
    }

    public static Double aimY(BuildTaskRecord.Target target) {
        return target.topHalf() == null
                ? null
                : target.pos().getY() + (target.topHalf() ? 0.72 : 0.28);
    }
}
