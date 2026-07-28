package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Dense, bounded voxel observation for model-authored construction and exact
 * edits. Unlike scan_blocks, this preserves every occupied cell in a caller
 * selected box and never searches outside that box.
 */
public final class ObserveVolumeTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final int MAX_AXIS = 24;
    private static final int MAX_VOLUME = 4_096;
    private static final int MAX_RETURNED_CELLS = 1_536;
    private static final int MAX_HORIZONTAL_DISTANCE = 32;
    private static final int MAX_VERTICAL_DISTANCE = 24;

    private record Args(int min_x, int min_y, int min_z,
                        int max_x, int max_y, int max_z,
                        Boolean include_air) {}

    @Override
    public String name() {
        return "observe_volume";
    }

    @Override
    public String description() {
        return "Read a precise 3D block snapshot inside an explicit absolute-coordinate box. "
                + "Use this as your detailed spatial vision for a building, tree, room, excavation, "
                + "or planned edit. The result has a block-state palette plus compact cells formatted "
                + "as [x,y,z,palette_index], so boundaries and connected shapes remain visible. It "
                + "never searches outside the requested box. Keep the box near you and as small as "
                + "the target allows; maximum 4096 inspected cells and 1536 returned non-air cells. "
                + "Air is omitted by default; set include_air only when cavities matter.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("min_x", "Inclusive minimum world X.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("min_y", "Inclusive minimum world Y.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("min_z", "Inclusive minimum world Z.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("max_x", "Inclusive maximum world X.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("max_y", "Inclusive maximum world Y.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("max_z", "Inclusive maximum world Z.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalBool("include_air", "Include air cells. Default false.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self,
                             Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        int minX = Math.min(a.min_x(), a.max_x());
        int minY = Math.min(a.min_y(), a.max_y());
        int minZ = Math.min(a.min_z(), a.max_z());
        int maxX = Math.max(a.min_x(), a.max_x());
        int maxY = Math.max(a.min_y(), a.max_y());
        int maxZ = Math.max(a.min_z(), a.max_z());

        int sizeX = checkedSpan(minX, maxX, "x");
        int sizeY = checkedSpan(minY, maxY, "y");
        int sizeZ = checkedSpan(minZ, maxZ, "z");
        long volume = (long) sizeX * sizeY * sizeZ;
        if (volume > MAX_VOLUME) {
            throw new IllegalArgumentException(
                    "observe_volume box is " + volume + " cells; maximum is " + MAX_VOLUME);
        }

        BlockPos feet = self.blockPosition();
        if (Math.max(Math.abs(minX - feet.getX()), Math.abs(maxX - feet.getX()))
                        > MAX_HORIZONTAL_DISTANCE
                || Math.max(Math.abs(minZ - feet.getZ()), Math.abs(maxZ - feet.getZ()))
                        > MAX_HORIZONTAL_DISTANCE
                || Math.max(Math.abs(minY - feet.getY()), Math.abs(maxY - feet.getY()))
                        > MAX_VERTICAL_DISTANCE) {
            throw new IllegalArgumentException(
                    "observe_volume box must remain within 32 horizontal and 24 vertical blocks of me");
        }

        boolean includeAir = Boolean.TRUE.equals(a.include_air());
        Map<String, Integer> paletteIndexes = new LinkedHashMap<>();
        JsonArray palette = new JsonArray();
        JsonArray cells = new JsonArray();
        int matching = 0;
        int unloaded = 0;

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!self.level().hasChunkAt(pos)) {
                        unloaded++;
                        continue;
                    }
                    BlockState state = self.level().getBlockState(pos);
                    if (!includeAir && state.isAir()) {
                        continue;
                    }
                    matching++;
                    if (cells.size() >= MAX_RETURNED_CELLS) {
                        continue;
                    }

                    String stateKey = stateKey(state);
                    Integer paletteIndex = paletteIndexes.get(stateKey);
                    if (paletteIndex == null) {
                        paletteIndex = palette.size();
                        paletteIndexes.put(stateKey, paletteIndex);
                        palette.add(paletteEntry(state));
                    }
                    JsonArray cell = new JsonArray();
                    cell.add(x);
                    cell.add(y);
                    cell.add(z);
                    cell.add(paletteIndex);
                    cells.add(cell);
                }
            }
        }

        JsonObject bounds = new JsonObject();
        addPos(bounds, "min", minX, minY, minZ);
        addPos(bounds, "max", maxX, maxY, maxZ);

        JsonObject root = new JsonObject();
        root.add("bounds", bounds);
        root.addProperty("volume", volume);
        root.addProperty("cell_format", "[x,y,z,palette_index]");
        root.add("palette", palette);
        root.add("cells", cells);
        root.addProperty("matching_cells", matching);
        root.addProperty("returned_cells", cells.size());
        root.addProperty("truncated", matching > MAX_RETURNED_CELLS);
        root.addProperty("unloaded_cells", unloaded);
        reply.accept(root.toString());
    }

    private static int checkedSpan(int min, int max, String axis) {
        long span = (long) max - min + 1;
        if (span < 1 || span > MAX_AXIS) {
            throw new IllegalArgumentException(
                    "observe_volume " + axis + " span must be from 1 to " + MAX_AXIS);
        }
        return (int) span;
    }

    private static void addPos(JsonObject root, String name, int x, int y, int z) {
        JsonObject pos = new JsonObject();
        pos.addProperty("x", x);
        pos.addProperty("y", y);
        pos.addProperty("z", z);
        root.add(name, pos);
    }

    private static JsonObject paletteEntry(BlockState state) {
        JsonObject entry = new JsonObject();
        entry.addProperty("block",
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        if (!state.getProperties().isEmpty()) {
            JsonObject properties = new JsonObject();
            for (Property<?> property : state.getProperties()) {
                properties.addProperty(property.getName(), propertyValue(state, property));
            }
            entry.add("properties", properties);
        }
        return entry;
    }

    private static String stateKey(BlockState state) {
        StringBuilder out = new StringBuilder(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        for (Property<?> property : state.getProperties()) {
            out.append('|').append(property.getName()).append('=')
                    .append(propertyValue(state, property));
        }
        return out.toString();
    }

    private static <T extends Comparable<T>> String propertyValue(
            BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
