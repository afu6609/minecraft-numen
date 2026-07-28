package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.ctx;
import static com.dwinovo.numen.task.TaskDispatch.dispatchAsync;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.task.BuildTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Exact, guarded one-block player action; deliberately performs no search. */
public final class BreakBlockTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final long TIMEOUT_TICKS = 30 * 20;

    private record Args(int x, int y, int z, String expected_block_id) {}

    @Override
    public String name() {
        return "break_block";
    }

    @Override
    public String description() {
        return "Break exactly one visible, reachable block at absolute x/y/z as a normal player. "
                + "You MUST provide expected_block_id from fresh observation; the action refuses if "
                + "the cell changed. It never searches for another block of the same type and never "
                + "walks to a substitute target. Use this primitive for precise corrections and "
                + "model-authored action sequences. If it is not currently visible and in reach, "
                + "observe and move closer first. For a verified list of many explicit cells, use "
                + "build with minecraft:air; never use mine to demolish or edit a structure.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "Exact target world X.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("y", "Exact target world Y.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("z", "Exact target world Z.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .string("expected_block_id",
                        "Namespaced block id currently observed at this exact cell.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        ResourceLocation expectedId = ResourceLocation.tryParse(a.expected_block_id());
        if (expectedId == null) {
            throw new IllegalArgumentException(
                    "expected_block_id must be a namespaced block id");
        }
        Block expected = BuiltInRegistries.BLOCK.get(expectedId);
        if (expected == null || expected == Blocks.AIR) {
            throw new IllegalArgumentException(
                    "expected_block_id must name a non-air block");
        }

        BlockPos pos = new BlockPos(a.x(), a.y(), a.z());
        if (!companion.level().hasChunkAt(pos)) {
            throw new IllegalArgumentException("target cell is not loaded; move closer first");
        }
        BlockState current = companion.level().getBlockState(pos);
        ResourceLocation currentId = BuiltInRegistries.BLOCK.getKey(current.getBlock());
        if (current.getBlock() != expected) {
            throw new IllegalArgumentException(
                    "stale observation: expected " + expectedId + " at "
                            + pos.toShortString() + " but found " + currentId
                            + "; observe again and replan");
        }
        if (current.getDestroySpeed(companion.level(), pos) < 0
                || BlockHelper.shouldAvoidBreaking(companion.level(), pos)) {
            throw new IllegalArgumentException(
                    "refusing to break protected or unbreakable block at "
                            + pos.toShortString());
        }
        if (MovementHelper.reachableAimPoint(companion, pos) == null) {
            throw new IllegalArgumentException(
                    "target " + pos.toShortString()
                            + " is not visible and in reach; move closer or clear the line of sight");
        }

        BuildTaskRecord.Target target = new BuildTaskRecord.Target(
                Blocks.AIR, Items.AIR, pos, "air", null, null, null);
        dispatchAsync(companion, new BuildTaskRecord(
                toolCallId,
                ctx(toolCallId, companion).deadline(TIMEOUT_TICKS),
                List.of(target),
                true,
                0),
                reply);
    }
}
