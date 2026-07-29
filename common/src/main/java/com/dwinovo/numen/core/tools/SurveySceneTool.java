package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.spatial.MinecraftSpatialSampler;
import com.dwinovo.numen.core.spatial.SpatialObject;
import com.dwinovo.numen.core.spatial.SpatialScene;
import com.dwinovo.numen.core.spatial.SpatialSceneAnalyzer;
import com.dwinovo.numen.core.spatial.SpatialSceneStore;
import com.dwinovo.numen.core.spatial.SpatialSnapshot;
import com.dwinovo.numen.core.spatial.VoxelPos;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * High-level spatial survey: exact server voxels in, compact semantic object
 * cards out.
 */
public final class SurveySceneTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final int DEFAULT_RADIUS = 14;
    private static final int DEFAULT_VERTICAL_RADIUS = 10;
    private static final int MAX_FOCUS_DISTANCE = 64;

    private final MinecraftSpatialSampler sampler = new MinecraftSpatialSampler();
    private final SpatialSceneAnalyzer analyzer = new SpatialSceneAnalyzer();

    private record Args(
            String anchor_mode,
            Integer x,
            Integer y,
            Integer z,
            Integer radius,
            Integer vertical_radius,
            String intent) {}

    private record Anchor(VoxelPos position, String source) {}

    @Override
    public String name() {
        return "survey_scene";
    }

    @Override
    public String description() {
        return "Build a compact server-side 3D scene graph around me, my owner, the block my "
                + "owner is looking at, or explicit coordinates. It recognizes exact connected "
                + "geometry and returns stable object ids for trees, building candidates, "
                + "doorway entrances, terrain depressions/pits, and sharp ground protrusions. "
                + "Use this before planning work such as 'this house', clearing dirt, filling "
                + "holes, logging, or preserving nearby structures. Semantics and old-world "
                + "creator attribution include confidence; unknown constructed structures are "
                + "protected by default. Follow with inspect_object for an object's detailed "
                + "geometry instead of repeatedly reading raw volume cells.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalEnum(
                        "anchor_mode",
                        "Survey center: self (default), owner, owner_focus (owner gaze ray), "
                                + "or coordinates.",
                        "self", "owner", "owner_focus", "coordinates")
                .optionalInteger("x", "Center X when anchor_mode=coordinates.",
                        Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalInteger("y", "Center Y when anchor_mode=coordinates.",
                        Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalInteger("z", "Center Z when anchor_mode=coordinates.",
                        Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalInteger("radius", "Horizontal radius, default 14.", 6, 20)
                .optionalInteger(
                        "vertical_radius", "Blocks above and below center, default 10.", 6, 12)
                .optionalString(
                        "intent",
                        "Short reason for the survey. Recorded in the result; geometry does not "
                                + "change based on model wording.")
                .build();
    }

    @Override
    public void onServerCall(
            String toolCallId,
            JsonObject args,
            NumenPlayer self,
            Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        int radius = parsed.radius() == null ? DEFAULT_RADIUS : parsed.radius();
        int verticalRadius = parsed.vertical_radius() == null
                ? DEFAULT_VERTICAL_RADIUS
                : parsed.vertical_radius();
        if (radius < 6 || radius > 20 || verticalRadius < 6 || verticalRadius > 12) {
            throw new IllegalArgumentException("survey radius is outside the allowed range");
        }

        Anchor anchor = resolveAnchor(self, parsed);
        SpatialSnapshot snapshot = sampler.sample(
                self.serverLevel(), anchor.position(), radius, verticalRadius);
        java.util.List<SpatialObject> objects = analyzer.analyze(snapshot);
        SpatialScene scene = SpatialSceneStore.save(
                self.getUUID(), anchor.source(), snapshot, objects);

        JsonObject root = new JsonObject();
        root.addProperty("scene_id", scene.id());
        root.addProperty("revision", scene.revision());
        root.addProperty("anchor_source", scene.anchorSource());
        root.add("anchor", SpatialToolJson.point(scene.anchor()));
        root.add("survey_bounds", SpatialToolJson.bounds(scene.bounds()));
        root.addProperty("sampled_non_air_cells", scene.sampledCells());
        root.addProperty("unloaded_columns", scene.unloadedColumns());
        root.addProperty("complete", scene.unloadedColumns() == 0);
        if (parsed.intent() != null && !parsed.intent().isBlank()) {
            root.addProperty("intent", parsed.intent().trim());
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        JsonArray cards = new JsonArray();
        for (SpatialObject object : scene.objects()) {
            counts.merge(object.kind(), 1, Integer::sum);
            cards.add(SpatialToolJson.objectCard(object));
        }
        root.add("object_counts", GSON.toJsonTree(counts));
        root.add("objects", cards);
        root.addProperty(
                "interpretation",
                "Bounds/cells are server-derived geometry. Labels and provenance are conservative "
                        + "inferences; inspect low-confidence objects before editing.");
        root.addProperty(
                "next_step",
                "Call inspect_object with scene_id and object_id for compact exact geometry.");
        reply.accept(root.toString());
    }

    private static Anchor resolveAnchor(NumenPlayer self, Args args) {
        String mode = args.anchor_mode() == null || args.anchor_mode().isBlank()
                ? "self"
                : args.anchor_mode().trim();
        return switch (mode) {
            case "self" -> new Anchor(voxel(self.blockPosition()), "self");
            case "owner" -> {
                ServerPlayer owner = requireOwner(self);
                yield new Anchor(voxel(owner.blockPosition()),
                        "owner:" + owner.getGameProfile().getName());
            }
            case "owner_focus" -> ownerFocus(self);
            case "coordinates" -> {
                if (args.x() == null || args.y() == null || args.z() == null) {
                    throw new IllegalArgumentException(
                            "x, y and z are all required for anchor_mode=coordinates");
                }
                yield new Anchor(
                        new VoxelPos(args.x(), args.y(), args.z()), "coordinates");
            }
            default -> throw new IllegalArgumentException("unsupported anchor_mode: " + mode);
        };
    }

    private static Anchor ownerFocus(NumenPlayer self) {
        ServerPlayer owner = requireOwner(self);
        Vec3 eye = owner.getEyePosition();
        Vec3 end = eye.add(owner.getViewVector(1.0F).scale(MAX_FOCUS_DISTANCE));
        BlockHitResult hit = owner.level().clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, owner));
        if (hit.getType() == HitResult.Type.MISS) {
            return new Anchor(
                    voxel(owner.blockPosition()),
                    "owner_focus_missed;fallback_owner:"
                            + owner.getGameProfile().getName());
        }
        return new Anchor(
                voxel(hit.getBlockPos()),
                "owner_focus:" + owner.getGameProfile().getName());
    }

    private static ServerPlayer requireOwner(NumenPlayer self) {
        ServerPlayer owner = self.resolveOwnerPlayer();
        if (owner == null) {
            throw new IllegalArgumentException(
                    "the companion owner is not online; use self or coordinates");
        }
        return owner;
    }

    private static VoxelPos voxel(BlockPos pos) {
        return new VoxelPos(pos.getX(), pos.getY(), pos.getZ());
    }
}
