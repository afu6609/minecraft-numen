package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.core.task.BuildTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Small world-local store for model-authored structure blueprints.
 *
 * <p>The blueprint is deliberately made of absolute explicit cells. It is not a
 * fuzzy material search: later build and demolition batches can only touch
 * coordinates that were recorded here.</p>
 */
final class StructureWorkflowStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "numen-structure-workflows.json";
    private static final int FILE_VERSION = 1;
    private static final int MAX_WORKFLOWS = 128;
    private static final Map<MinecraftServer, StoreData> CACHE = new WeakHashMap<>();

    private StructureWorkflowStore() {}

    static synchronized Workflow put(NumenPlayer self, String requestedId,
                                     String name, String goal, boolean allowReplace,
                                     List<BuildTaskRecord.Target> targets) {
        MinecraftServer server = requireServer(self);
        StoreData data = data(server);
        String ownerUuid = self.getUUID().toString();
        Workflow previous = null;
        if (requestedId != null && !requestedId.isBlank()) {
            previous = findOwned(data, ownerUuid, requestedId.trim());
            if (previous == null) {
                throw new IllegalArgumentException(
                        "unknown structure workflow " + requestedId + " for this companion");
            }
        } else if (data.workflows.size() >= MAX_WORKFLOWS) {
            throw new IllegalStateException(
                    "structure workflow store is full; keep or remove old manifests before creating another");
        }

        Map<BlockPos, StateSpec> originalByPos = new LinkedHashMap<>();
        long createdAt = System.currentTimeMillis();
        String id = newId();
        if (previous != null) {
            id = previous.id;
            createdAt = previous.createdAt;
            for (Cell cell : previous.cells) {
                originalByPos.put(cell.pos().immutable(), cell.before);
            }
        }

        Workflow workflow = new Workflow();
        workflow.id = id;
        workflow.ownerUuid = ownerUuid;
        workflow.ownerName = self.getScoreboardName();
        workflow.name = clean(name, "name", 80);
        workflow.goal = clean(goal, "goal", 600);
        workflow.allowReplace = allowReplace;
        workflow.createdAt = createdAt;
        workflow.updatedAt = System.currentTimeMillis();
        workflow.revision = previous == null ? 1 : nextRevision(previous.revision);
        workflow.lastOperation = "plan";
        workflow.cells = new ArrayList<>(targets.size());
        for (BuildTaskRecord.Target target : targets) {
            StateSpec before = originalByPos.get(target.pos());
            if (before == null) {
                before = StateSpec.from(self.level().getBlockState(target.pos()));
            }
            workflow.cells.add(Cell.from(target, before));
        }

        StoreData replacement = replacing(data, previous, workflow);
        save(server, replacement);
        CACHE.put(server, replacement);
        return workflow;
    }

    static synchronized Workflow patch(
            NumenPlayer self,
            String requestedId,
            long expectedRevision,
            List<BuildTaskRecord.Target> upserts,
            List<BlockPos> removals) {
        MinecraftServer server = requireServer(self);
        StoreData data = data(server);
        Workflow previous = findOwned(
                data, self.getUUID().toString(), requireExactId(requestedId));
        if (previous == null) {
            throw new IllegalArgumentException(
                    "unknown structure workflow " + requestedId + " for this companion");
        }

        Workflow workflow = patchedCopy(
                previous,
                expectedRevision,
                upserts,
                removals,
                pos -> self.level().getBlockState(pos),
                targets -> StructurePlanTool.validateBounds(self, targets));

        StoreData replacement = replacing(data, previous, workflow);
        save(server, replacement);
        CACHE.put(server, replacement);
        return workflow;
    }

    static synchronized Workflow resolve(NumenPlayer self, String requestedId) {
        StoreData data = data(requireServer(self));
        String ownerUuid = self.getUUID().toString();
        if (requestedId != null && !requestedId.isBlank()
                && !requestedId.trim().equalsIgnoreCase("latest")) {
            Workflow workflow = findOwned(data, ownerUuid, requestedId.trim());
            if (workflow == null) {
                throw new IllegalArgumentException(
                        "unknown structure workflow " + requestedId + " for this companion");
            }
            return workflow;
        }
        return data.workflows.stream()
                .filter(workflow -> ownerUuid.equals(workflow.ownerUuid))
                .max(Comparator.comparingLong(workflow -> workflow.updatedAt))
                .orElseThrow(() -> new IllegalArgumentException(
                        "this companion has no saved structure workflow yet"));
    }

    static synchronized void touch(NumenPlayer self, Workflow workflow, String operation) {
        MinecraftServer server = requireServer(self);
        workflow.updatedAt = System.currentTimeMillis();
        workflow.lastOperation = operation;
        save(server, data(server));
    }

    /**
     * Build a fully validated replacement without mutating the saved workflow.
     *
     * <p>Package visibility keeps patch semantics directly unit-testable without
     * requiring a filesystem-backed Minecraft server.</p>
     */
    static Workflow patchedCopy(
            Workflow previous,
            long expectedRevision,
            List<BuildTaskRecord.Target> upserts,
            List<BlockPos> removals,
            Function<BlockPos, BlockState> currentState) {
        return patchedCopy(
                previous,
                expectedRevision,
                upserts,
                removals,
                currentState,
                ignored -> {});
    }

    /**
     * Variant used by the live store to validate the complete patched manifest
     * before any newly-added coordinate is read from the world. This prevents a
     * rejected far-away patch from synchronously loading or probing that chunk.
     */
    static Workflow patchedCopy(
            Workflow previous,
            long expectedRevision,
            List<BuildTaskRecord.Target> upserts,
            List<BlockPos> removals,
            Function<BlockPos, BlockState> currentState,
            Consumer<List<BuildTaskRecord.Target>> finalManifestValidator) {
        if (previous == null) {
            throw new IllegalArgumentException("workflow must not be null");
        }
        if (currentState == null || finalManifestValidator == null) {
            throw new IllegalArgumentException(
                    "patch state reader and manifest validator must not be null");
        }
        long revision = normalizedRevision(previous.revision);
        if (expectedRevision != revision) {
            throw new IllegalArgumentException(
                    "structure workflow revision changed: expected "
                            + expectedRevision + " but current revision is " + revision
                            + "; call structure_status and rebase the patch");
        }
        if (upserts == null) {
            upserts = List.of();
        }
        if (removals == null) {
            removals = List.of();
        }
        if (upserts.isEmpty() && removals.isEmpty()) {
            throw new IllegalArgumentException(
                    "structure patch must upsert or remove at least one cell");
        }
        if ((long) upserts.size() + removals.size() > StructurePlanTool.MAX_CELLS) {
            throw new IllegalArgumentException(
                    "structure patch accepts at most "
                            + StructurePlanTool.MAX_CELLS + " operations");
        }

        Map<BlockPos, BuildTaskRecord.Target> upsertByPos = new LinkedHashMap<>();
        for (BuildTaskRecord.Target target : upserts) {
            if (target == null) {
                throw new IllegalArgumentException("upsert cells must not be null");
            }
            BlockPos key = target.pos().immutable();
            if (upsertByPos.putIfAbsent(key, target) != null) {
                throw new IllegalArgumentException(
                        "duplicate upsert cell " + target.pos().toShortString());
            }
        }
        Map<BlockPos, BlockPos> removeByPos = new LinkedHashMap<>();
        for (BlockPos pos : removals) {
            if (pos == null) {
                throw new IllegalArgumentException("remove positions must not be null");
            }
            BlockPos key = pos.immutable();
            if (removeByPos.putIfAbsent(key, key) != null) {
                throw new IllegalArgumentException(
                        "duplicate remove position " + pos.toShortString());
            }
            if (upsertByPos.containsKey(key)) {
                throw new IllegalArgumentException(
                        "one patch cannot both remove and upsert "
                                + pos.toShortString());
            }
        }

        Map<BlockPos, Cell> previousByPos = new LinkedHashMap<>();
        for (Cell cell : previous.cells) {
            BlockPos key = cell.pos().immutable();
            if (previousByPos.putIfAbsent(key, cell) != null) {
                throw new IllegalStateException(
                        "saved structure workflow contains duplicate cell "
                                + key.toShortString());
            }
        }
        for (BlockPos pos : removeByPos.values()) {
            if (!previousByPos.containsKey(pos)) {
                throw new IllegalArgumentException(
                        "cannot remove unsaved structure cell " + pos.toShortString());
            }
        }
        for (Map.Entry<BlockPos, BuildTaskRecord.Target> entry : upsertByPos.entrySet()) {
            Cell saved = previousByPos.get(entry.getKey());
            if (saved == null) {
                continue;
            }
            BuildTaskRecord.Target target = entry.getValue();
            String itemId = BuiltInRegistries.ITEM.getKey(target.item()).toString();
            if (saved.desired.toState().equals(target.desiredState())
                    && saved.itemId.equals(itemId)) {
                throw new IllegalArgumentException(
                        "upsert does not change saved structure cell "
                                + target.pos().toShortString());
            }
        }

        List<BuildTaskRecord.Target> finalTargets = new ArrayList<>(
                previous.cells.size() - removeByPos.size() + upsertByPos.size());
        Set<BlockPos> appliedUpserts = new LinkedHashSet<>();
        for (Cell cell : previous.cells) {
            BlockPos key = cell.pos().immutable();
            if (removeByPos.containsKey(key)) {
                continue;
            }
            BuildTaskRecord.Target replacement = upsertByPos.get(key);
            if (replacement == null) {
                finalTargets.add(target(cell));
            } else {
                finalTargets.add(replacement);
                appliedUpserts.add(key);
            }
        }
        for (Map.Entry<BlockPos, BuildTaskRecord.Target> entry : upsertByPos.entrySet()) {
            if (appliedUpserts.contains(entry.getKey())) {
                continue;
            }
            finalTargets.add(entry.getValue());
        }

        if (finalTargets.isEmpty()) {
            throw new IllegalArgumentException(
                    "structure patch cannot remove every saved cell");
        }
        if (finalTargets.size() > StructurePlanTool.MAX_CELLS) {
            throw new IllegalArgumentException(
                    "patched blueprint accepts at most "
                            + StructurePlanTool.MAX_CELLS + " cells");
        }
        finalManifestValidator.accept(List.copyOf(finalTargets));

        Workflow workflow = copyMetadata(previous);
        workflow.updatedAt = System.currentTimeMillis();
        workflow.revision = nextRevision(revision);
        workflow.lastOperation = "patch";
        workflow.cells = new ArrayList<>(finalTargets.size());

        appliedUpserts.clear();
        for (Cell cell : previous.cells) {
            BlockPos key = cell.pos().immutable();
            if (removeByPos.containsKey(key)) {
                continue;
            }
            BuildTaskRecord.Target replacement = upsertByPos.get(key);
            if (replacement == null) {
                workflow.cells.add(copyCell(cell));
            } else {
                workflow.cells.add(Cell.from(replacement, copyState(cell.before)));
                appliedUpserts.add(key);
            }
        }
        for (Map.Entry<BlockPos, BuildTaskRecord.Target> entry : upsertByPos.entrySet()) {
            if (appliedUpserts.contains(entry.getKey())) {
                continue;
            }
            BuildTaskRecord.Target target = entry.getValue();
            BlockState before = currentState.apply(target.pos());
            if (before == null) {
                throw new IllegalStateException(
                        "could not read original block state at "
                                + target.pos().toShortString());
            }
            workflow.cells.add(Cell.from(target, StateSpec.from(before)));
        }
        return workflow;
    }

    static BuildTaskRecord.Target target(Cell cell) {
        BlockState desired = cell.desired.toState();
        Item item = desired.isAir() ? Items.AIR : ToolArgs.parseItem(cell.itemId);
        Direction facing = null;
        if (desired.hasProperty(BlockStateProperties.FACING)) {
            facing = desired.getValue(BlockStateProperties.FACING);
        } else if (desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            facing = desired.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        Direction.Axis axis = null;
        if (desired.hasProperty(BlockStateProperties.AXIS)) {
            axis = desired.getValue(BlockStateProperties.AXIS);
        } else if (desired.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            axis = desired.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        }
        Boolean topHalf = null;
        if (desired.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            topHalf = desired.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.TOP;
        } else if (desired.hasProperty(BlockStateProperties.HALF)) {
            topHalf = desired.getValue(BlockStateProperties.HALF)
                    == net.minecraft.world.level.block.state.properties.Half.TOP;
        }
        String label = BuiltInRegistries.BLOCK.getKey(desired.getBlock()).getPath();
        return new BuildTaskRecord.Target(
                desired, item, cell.pos(), label, facing, axis, topHalf);
    }

    private static Workflow findOwned(StoreData data, String ownerUuid, String id) {
        for (Workflow workflow : data.workflows) {
            if (id.equals(workflow.id) && ownerUuid.equals(workflow.ownerUuid)) {
                return workflow;
            }
        }
        return null;
    }

    private static StoreData replacing(
            StoreData data, Workflow previous, Workflow workflow) {
        StoreData replacement = new StoreData();
        replacement.version = data.version;
        replacement.workflows = new ArrayList<>(data.workflows);
        if (previous != null) {
            replacement.workflows.remove(previous);
        }
        replacement.workflows.add(workflow);
        return replacement;
    }

    private static String requireExactId(String requestedId) {
        if (requestedId == null || requestedId.isBlank()
                || requestedId.trim().equalsIgnoreCase("latest")) {
            throw new IllegalArgumentException(
                    "structure_patch requires the exact workflow_id returned by structure_plan");
        }
        return requestedId.trim();
    }

    private static long normalizedRevision(long revision) {
        return Math.max(1, revision);
    }

    private static long nextRevision(long revision) {
        long normalized = normalizedRevision(revision);
        if (normalized == Long.MAX_VALUE) {
            throw new IllegalStateException("structure workflow revision exhausted");
        }
        return normalized + 1;
    }

    private static Workflow copyMetadata(Workflow source) {
        Workflow copy = new Workflow();
        copy.id = source.id;
        copy.ownerUuid = source.ownerUuid;
        copy.ownerName = source.ownerName;
        copy.name = source.name;
        copy.goal = source.goal;
        copy.allowReplace = source.allowReplace;
        copy.createdAt = source.createdAt;
        copy.updatedAt = source.updatedAt;
        copy.revision = normalizedRevision(source.revision);
        copy.lastOperation = source.lastOperation;
        return copy;
    }

    private static Cell copyCell(Cell source) {
        Cell copy = new Cell();
        copy.x = source.x;
        copy.y = source.y;
        copy.z = source.z;
        copy.itemId = source.itemId;
        copy.desired = copyState(source.desired);
        copy.before = copyState(source.before);
        return copy;
    }

    private static StateSpec copyState(StateSpec source) {
        if (source == null) {
            return null;
        }
        StateSpec copy = new StateSpec();
        copy.blockId = source.blockId;
        copy.properties = source.properties == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(source.properties);
        return copy;
    }

    private static String newId() {
        String stamp = Long.toString(Instant.now().getEpochSecond(), 36);
        return "structure-" + stamp + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String clean(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        String clean = value.trim();
        if (clean.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must be at most " + maxLength + " characters");
        }
        return clean;
    }

    private static MinecraftServer requireServer(NumenPlayer self) {
        MinecraftServer server = self.getServer();
        if (server == null) {
            throw new IllegalStateException("structure workflows require a running server");
        }
        return server;
    }

    private static StoreData data(MinecraftServer server) {
        StoreData cached = CACHE.get(server);
        if (cached != null) {
            return cached;
        }
        Path path = path(server);
        StoreData loaded = new StoreData();
        if (Files.exists(path)) {
            try {
                loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8),
                        StoreData.class);
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException(
                        "could not read structure workflow store " + path, error);
            }
        }
        if (loaded == null) {
            loaded = new StoreData();
        }
        if (loaded.version != FILE_VERSION) {
            throw new IllegalStateException(
                    "unsupported structure workflow store version " + loaded.version);
        }
        if (loaded.workflows == null) {
            loaded.workflows = new ArrayList<>();
        }
        for (Workflow workflow : loaded.workflows) {
            if (workflow.revision < 1) {
                workflow.revision = 1;
            }
        }
        CACHE.put(server, loaded);
        return loaded;
    }

    private static void save(MinecraftServer server, StoreData data) {
        Path path = path(server);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temp, GSON.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException(
                    "could not save structure workflow store " + path, error);
        }
    }

    private static Path path(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(FILE_NAME);
    }

    static final class Workflow {
        String id;
        String ownerUuid;
        String ownerName;
        String name;
        String goal;
        boolean allowReplace;
        long createdAt;
        long updatedAt;
        long revision = 1;
        String lastOperation;
        List<Cell> cells = new ArrayList<>();
    }

    static final class Cell {
        int x;
        int y;
        int z;
        String itemId;
        StateSpec desired;
        StateSpec before;

        static Cell from(BuildTaskRecord.Target target, StateSpec before) {
            Cell cell = new Cell();
            cell.x = target.pos().getX();
            cell.y = target.pos().getY();
            cell.z = target.pos().getZ();
            cell.itemId = BuiltInRegistries.ITEM.getKey(target.item()).toString();
            cell.desired = StateSpec.from(target.desiredState());
            cell.before = before;
            return cell;
        }

        BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    static final class StateSpec {
        String blockId;
        Map<String, String> properties = new LinkedHashMap<>();

        static StateSpec from(BlockState state) {
            StateSpec spec = new StateSpec();
            spec.blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            for (Property<?> property : state.getProperties()) {
                spec.properties.put(property.getName(), propertyValue(state, property));
            }
            return spec;
        }

        BlockState toState() {
            ResourceLocation id = ResourceLocation.tryParse(blockId);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                throw new IllegalStateException(
                        "saved workflow refers to unknown block " + blockId);
            }
            return BuildTool.applyProperties(
                    BuiltInRegistries.BLOCK.get(id).defaultBlockState(), properties);
        }

        private static <T extends Comparable<T>> String propertyValue(
                BlockState state, Property<T> property) {
            return property.getName(state.getValue(property));
        }
    }

    private static final class StoreData {
        int version = FILE_VERSION;
        List<Workflow> workflows = new ArrayList<>();
    }
}
