package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.spatial.SpatialBounds;
import com.dwinovo.numen.core.spatial.SpatialObject;
import com.dwinovo.numen.core.spatial.VoxelPos;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Compact JSON representation shared by spatial perception tools. */
final class SpatialToolJson {

    private static final Gson GSON = new Gson();

    private SpatialToolJson() {}

    static JsonObject objectCard(SpatialObject object) {
        JsonObject json = new JsonObject();
        json.addProperty("id", object.id());
        json.addProperty("kind", object.kind());
        json.addProperty("label", object.label());
        json.addProperty("confidence", object.confidence());
        json.add("bounds", bounds(object.bounds()));
        json.add("center", point(
                object.bounds().centerX(),
                object.bounds().centerY(),
                object.bounds().centerZ()));
        json.addProperty("geometry_cells", object.cells().size());
        json.add("features", GSON.toJsonTree(object.features()));
        JsonObject provenance = new JsonObject();
        provenance.addProperty("classification", object.provenance());
        provenance.addProperty("confidence", object.provenanceConfidence());
        provenance.addProperty("protection", object.protection());
        json.add("provenance", provenance);
        JsonArray relations = new JsonArray();
        object.relations().forEach(relations::add);
        json.add("relations", relations);
        return json;
    }

    static JsonObject bounds(SpatialBounds bounds) {
        JsonObject json = new JsonObject();
        json.add("min", point(bounds.minX(), bounds.minY(), bounds.minZ()));
        json.add("max", point(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
        JsonObject size = new JsonObject();
        size.addProperty("x", bounds.sizeX());
        size.addProperty("y", bounds.sizeY());
        size.addProperty("z", bounds.sizeZ());
        json.add("size", size);
        return json;
    }

    static JsonObject point(VoxelPos pos) {
        return point(pos.x(), pos.y(), pos.z());
    }

    static JsonObject point(double x, double y, double z) {
        JsonObject json = new JsonObject();
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("z", z);
        return json;
    }
}
