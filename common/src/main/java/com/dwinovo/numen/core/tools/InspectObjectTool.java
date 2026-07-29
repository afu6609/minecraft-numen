package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.spatial.SpatialObject;
import com.dwinovo.numen.core.spatial.SpatialScene;
import com.dwinovo.numen.core.spatial.SpatialSceneStore;
import com.dwinovo.numen.core.spatial.VoxelPos;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Expands one stable object id without returning an entire raw survey volume. */
public final class InspectObjectTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final int MAX_SPANS = 512;
    private static final int MAX_FULL_CELLS = 768;

    private record Args(String object_id, String scene_id, String detail) {}

    @Override
    public String name() {
        return "inspect_object";
    }

    @Override
    public String description() {
        return "Inspect one object returned by survey_scene. Returns its semantic card, current "
                + "material histogram, exact bounds, relations, and compact y/z/x geometry spans. "
                + "Use detail=full only for a small object when individual live block coordinates "
                + "are needed; large outputs are bounded. Object ids stay stable across nearby "
                + "re-surveys, and the latest scene is used if scene_id is omitted.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("object_id", "Stable object id from survey_scene, e.g. building-1.")
                .optionalString(
                        "scene_id",
                        "Scene revision to inspect. Omit for the latest retained scene.")
                .optionalEnum(
                        "detail",
                        "summary, geometry (default), or full bounded live cells.",
                        "summary", "geometry", "full")
                .build();
    }

    @Override
    public void onServerCall(
            String toolCallId,
            JsonObject args,
            NumenPlayer self,
            Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        if (parsed.object_id() == null || parsed.object_id().isBlank()) {
            throw new IllegalArgumentException("object_id is required");
        }
        SpatialScene scene = SpatialSceneStore.get(self.getUUID(), parsed.scene_id());
        if (scene == null) {
            throw new IllegalArgumentException(
                    "scene not found or expired; call survey_scene again");
        }
        SpatialObject object = scene.objects().stream()
                .filter(candidate -> candidate.id().equals(parsed.object_id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "object_id not found in " + scene.id() + "; call survey_scene again"));

        String detail = parsed.detail() == null ? "geometry" : parsed.detail();
        JsonObject root = new JsonObject();
        root.addProperty("scene_id", scene.id());
        root.addProperty("scene_revision", scene.revision());
        root.add("object", SpatialToolJson.objectCard(object));
        root.add("live_materials", liveMaterials(self, object));

        if (!"summary".equals(detail)) {
            List<VoxelPos> sorted = object.cells().stream()
                    .sorted(Comparator.comparingInt(VoxelPos::y)
                            .thenComparingInt(VoxelPos::z)
                            .thenComparingInt(VoxelPos::x))
                    .toList();
            JsonArray spans = compactSpans(sorted);
            root.addProperty("span_format", "y:z:x_start..x_end");
            root.add("geometry_spans", spans);
            root.addProperty("geometry_spans_truncated", countSpans(sorted) > MAX_SPANS);
            root.addProperty(
                    "geometry_note",
                    "Spans describe classified object cells, not every air cell inside its bounds.");

            if ("full".equals(detail)) {
                JsonArray cells = new JsonArray();
                int limit = Math.min(sorted.size(), MAX_FULL_CELLS);
                for (int i = 0; i < limit; i++) {
                    VoxelPos pos = sorted.get(i);
                    BlockState state = self.level().getBlockState(
                            new BlockPos(pos.x(), pos.y(), pos.z()));
                    JsonArray cell = new JsonArray();
                    cell.add(pos.x());
                    cell.add(pos.y());
                    cell.add(pos.z());
                    cell.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    cells.add(cell);
                }
                root.addProperty("cell_format", "[x,y,z,current_block_id]");
                root.add("live_cells", cells);
                root.addProperty("live_cells_truncated", sorted.size() > MAX_FULL_CELLS);
            }
        }
        reply.accept(root.toString());
    }

    private static JsonObject liveMaterials(NumenPlayer self, SpatialObject object) {
        Map<String, Integer> materials = new LinkedHashMap<>();
        int changedToAir = 0;
        int unloaded = 0;
        for (VoxelPos pos : object.cells()) {
            BlockPos blockPos = new BlockPos(pos.x(), pos.y(), pos.z());
            if (!self.level().hasChunkAt(blockPos)) {
                unloaded++;
                continue;
            }
            BlockState state = self.level().getBlockState(blockPos);
            if (state.isAir()) {
                changedToAir++;
                continue;
            }
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            materials.merge(id, 1, Integer::sum);
        }
        JsonObject root = new JsonObject();
        root.add("counts", GSON.toJsonTree(materials));
        root.addProperty("classified_cells_now_air", changedToAir);
        root.addProperty("unloaded_cells", unloaded);
        root.addProperty("missing_classified_cells", changedToAir + unloaded);
        root.addProperty("resurvey_recommended", changedToAir > 0 || unloaded > 0);
        root.addProperty(
                "freshness_note",
                "This live read detects missing classified cells. Replaced non-air materials "
                        + "require a new survey to reclassify the object.");
        return root;
    }

    private static JsonArray compactSpans(List<VoxelPos> sorted) {
        JsonArray spans = new JsonArray();
        for (String span : spanStrings(sorted)) {
            if (spans.size() >= MAX_SPANS) break;
            spans.add(span);
        }
        return spans;
    }

    private static int countSpans(List<VoxelPos> sorted) {
        return spanStrings(sorted).size();
    }

    private static List<String> spanStrings(List<VoxelPos> sorted) {
        List<String> spans = new ArrayList<>();
        int index = 0;
        while (index < sorted.size()) {
            VoxelPos first = sorted.get(index);
            int y = first.y();
            int z = first.z();
            int startX = first.x();
            int endX = startX;
            index++;
            while (index < sorted.size()) {
                VoxelPos next = sorted.get(index);
                if (next.y() != y || next.z() != z || next.x() > endX + 1) break;
                endX = Math.max(endX, next.x());
                index++;
            }
            spans.add(y + ":" + z + ":" + startX + ".." + endX);
        }
        return spans;
    }
}
