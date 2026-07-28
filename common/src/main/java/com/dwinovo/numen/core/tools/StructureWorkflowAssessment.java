package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.task.BuildTaskRecord;
import com.dwinovo.numen.core.task.MultiBlockPlacement;
import com.dwinovo.numen.core.task.PlayerInv;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Live comparison between a saved structure blueprint and the loaded world. */
final class StructureWorkflowAssessment {

    final StructureWorkflowStore.Workflow workflow;
    final List<BuildTaskRecord.Target> buildCandidates = new ArrayList<>();
    final List<BuildTaskRecord.Target> demolitionCandidates = new ArrayList<>();
    final List<BlockPos> buildConflicts = new ArrayList<>();
    final List<BlockPos> demolitionConflicts = new ArrayList<>();
    final Map<Item, Integer> neededMaterials = new LinkedHashMap<>();
    int matched;
    int alreadyRemoved;
    int unloaded;
    int occupiedWrong;

    private StructureWorkflowAssessment(StructureWorkflowStore.Workflow workflow) {
        this.workflow = workflow;
    }

    static StructureWorkflowAssessment inspect(
            StructureWorkflowStore.Workflow workflow, NumenPlayer self) {
        StructureWorkflowAssessment out = new StructureWorkflowAssessment(workflow);
        for (StructureWorkflowStore.Cell cell : workflow.cells) {
            BuildTaskRecord.Target target = StructureWorkflowStore.target(cell);
            BlockPos pos = target.pos();
            if (!self.level().hasChunkAt(pos)) {
                out.unloaded++;
                if (!target.desiredState().isAir()) {
                    out.neededMaterials.merge(target.item(), 1, Integer::sum);
                }
                continue;
            }

            BlockState current = self.level().getBlockState(pos);
            if (MultiBlockPlacement.matches(self.level(), target)) {
                out.matched++;
            } else {
                out.buildCandidates.add(target);
                if (!current.isAir()) {
                    out.occupiedWrong++;
                    if (!workflow.allowReplace) {
                        out.buildConflicts.add(pos.immutable());
                    }
                }
                if (!target.desiredState().isAir()) {
                    out.neededMaterials.merge(target.item(), 1, Integer::sum);
                }
            }

            if (target.desiredState().isAir() || current.isAir()) {
                out.alreadyRemoved++;
            } else if (current.getBlock() == target.block()) {
                out.demolitionCandidates.add(target);
            } else {
                out.demolitionConflicts.add(pos.immutable());
            }
        }
        return out;
    }

    JsonObject render(NumenPlayer self, String operation) {
        boolean demolition = "demolish".equals(operation);
        JsonObject root = new JsonObject();
        root.addProperty("workflow_id", workflow.id);
        root.addProperty("name", workflow.name);
        root.addProperty("goal", workflow.goal);
        root.addProperty("owner_companion", workflow.ownerName);
        root.addProperty("operation", demolition ? "demolish" : "build");
        root.addProperty("allow_replace", workflow.allowReplace);
        root.addProperty("saved_cells", workflow.cells.size());
        root.addProperty("unloaded_cells", unloaded);
        root.addProperty("last_operation", workflow.lastOperation);
        root.add("bounds", bounds());

        JsonObject progress = new JsonObject();
        if (demolition) {
            progress.addProperty("remaining_matching_blueprint_blocks",
                    demolitionCandidates.size());
            progress.addProperty("already_air_or_blueprint_air", alreadyRemoved);
            progress.addProperty("conflicts_skipped", demolitionConflicts.size());
            progress.add("conflict_samples", positions(demolitionConflicts, 8));
        } else {
            progress.addProperty("matched", matched);
            progress.addProperty("remaining", buildCandidates.size() + unloaded);
            progress.addProperty("occupied_wrong", occupiedWrong);
            progress.addProperty("blocking_conflicts", buildConflicts.size());
            progress.add("conflict_samples", positions(buildConflicts, 8));
        }
        root.add("progress", progress);
        root.add("materials", materials(self));

        String phase;
        String next;
        if (demolition) {
            if (!demolitionCandidates.isEmpty()) {
                phase = "ready_to_demolish";
                next = "Call structure_execute with operation=demolish; it will use an exact saved-cell batch.";
            } else if (!demolitionConflicts.isEmpty()) {
                phase = "conflicts";
                next = "Re-observe the conflict cells; they no longer match this blueprint and will not be touched.";
            } else {
                phase = "complete";
                next = "The saved blueprint blocks are gone.";
            }
        } else if (unloaded > 0) {
            phase = "needs_loaded_site";
            next = "Move near the saved bounds so every blueprint cell is loaded, then check status again.";
        } else if (!buildConflicts.isEmpty()) {
            phase = "site_conflicts";
            next = "Revise the plan/site, or explicitly replace this workflow with allow_replace=true after observing the conflicts.";
        } else if (!missingMaterials(self).isEmpty()) {
            phase = "needs_materials";
            next = "Use the material ledger to gather or craft the exact shortfalls, then check status again.";
        } else if (!buildCandidates.isEmpty()) {
            phase = "ready_to_build";
            next = "Call structure_execute with operation=build; it will run one bounded checkpoint batch.";
        } else {
            phase = "complete";
            next = "Re-observe the finished structure and report only after checking important details.";
        }
        root.addProperty("phase", phase);
        root.addProperty("next", next);
        return root;
    }

    Map<Item, Integer> missingMaterials(NumenPlayer self) {
        Map<Item, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : neededMaterials.entrySet()) {
            int shortfall = entry.getValue()
                    - PlayerInv.count(self.getInventory(), entry.getKey());
            if (shortfall > 0) {
                missing.put(entry.getKey(), shortfall);
            }
        }
        return missing;
    }

    private JsonObject materials(NumenPlayer self) {
        JsonObject root = new JsonObject();
        for (Map.Entry<Item, Integer> entry : neededMaterials.entrySet()) {
            if (entry.getKey() == Items.AIR) {
                continue;
            }
            int have = PlayerInv.count(self.getInventory(), entry.getKey());
            JsonObject line = new JsonObject();
            line.addProperty("needed_for_remaining", entry.getValue());
            line.addProperty("carrying", have);
            line.addProperty("missing", Math.max(0, entry.getValue() - have));
            root.add(BuiltInRegistries.ITEM.getKey(entry.getKey()).toString(), line);
        }
        return root;
    }

    private JsonObject bounds() {
        JsonObject root = new JsonObject();
        if (workflow.cells.isEmpty()) {
            return root;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (StructureWorkflowStore.Cell cell : workflow.cells) {
            minX = Math.min(minX, cell.x);
            minY = Math.min(minY, cell.y);
            minZ = Math.min(minZ, cell.z);
            maxX = Math.max(maxX, cell.x);
            maxY = Math.max(maxY, cell.y);
            maxZ = Math.max(maxZ, cell.z);
        }
        root.add("min", pos(minX, minY, minZ));
        root.add("max", pos(maxX, maxY, maxZ));
        return root;
    }

    private static JsonArray positions(List<BlockPos> positions, int limit) {
        JsonArray out = new JsonArray();
        for (int index = 0; index < Math.min(limit, positions.size()); index++) {
            BlockPos pos = positions.get(index);
            JsonArray cell = new JsonArray();
            cell.add(pos.getX());
            cell.add(pos.getY());
            cell.add(pos.getZ());
            out.add(cell);
        }
        return out;
    }

    private static JsonObject pos(int x, int y, int z) {
        JsonObject out = new JsonObject();
        out.addProperty("x", x);
        out.addProperty("y", y);
        out.addProperty("z", z);
        return out;
    }
}
