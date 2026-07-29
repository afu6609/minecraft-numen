package com.dwinovo.numen.core.task.survival.shelter;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShelterVerifierTest {

    @BeforeAll
    static void bootMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void acceptsIntactClosedAndLitHouse() {
        ShelterBlueprint blueprint = blueprint();
        FakeWorld world = intactWorld(blueprint, 8);

        ShelterVerification result =
                ShelterVerifier.verify(blueprint, world);

        assertEquals(ShelterVerification.Status.SAFE, result.status());
        assertTrue(result.safe());
        assertTrue(result.preferredSafeStance().isPresent());
        assertEquals(0, result.darkStanceCount());
        assertEquals(8, result.minimumBlockLight());
        assertTrue(result.doors().stream().allMatch(
                door -> door.present() && !door.open()));
        assertEquals(world.reads, result.uniqueWorldSamples());
    }

    @Test
    void rejectsOpenDoorAndZeroLightInterior() {
        ShelterBlueprint blueprint = blueprint();
        FakeWorld world = intactWorld(blueprint, 0);
        ShelterBlueprint.Doorway door = blueprint.doors().get(0);
        world.blocks.put(door.lower(), sample(
                door.expectedBlockId(), false, false,
                false, true, true, 0));

        ShelterVerification result =
                ShelterVerifier.verify(blueprint, world);

        assertEquals(ShelterVerification.Status.UNSAFE, result.status());
        assertFalse(result.safe());
        assertTrue(result.doors().get(0).open());
        assertTrue(result.darkStanceCount() > 0);
        assertTrue(result.reasons().stream()
                .anyMatch(reason -> reason.contains("door is open")));
        assertTrue(result.reasons().stream()
                .anyMatch(reason -> reason.contains("zero block light")));
    }

    @Test
    void detectsRoofBreachEvenWhenOtherCellsMatch() {
        ShelterBlueprint blueprint = blueprint();
        FakeWorld world = intactWorld(blueprint, 8);
        BlockPos roof = new BlockPos(2, blueprint.roofY(), 2);
        world.blocks.put(roof, sample(
                "minecraft:air", true, false,
                false, false, false, 15));

        ShelterVerification result =
                ShelterVerifier.verify(blueprint, world);

        assertEquals(ShelterVerification.Status.UNSAFE, result.status());
        assertTrue(result.breachedShellCells() > 0);
        assertTrue(result.mismatchedBlueprintCells() > 0);
        assertTrue(result.breachSamples().contains(roof));
    }

    @Test
    void refusesToVerifySameCoordinatesInAnotherDimension() {
        ShelterBlueprint blueprint = blueprint();
        FakeWorld world = intactWorld(blueprint, 8);
        world.dimensionId = "minecraft:the_nether";

        ShelterVerification result =
                ShelterVerifier.verify(blueprint, world);

        assertEquals(
                ShelterVerification.Status.WRONG_DIMENSION,
                result.status());
        assertEquals(0, result.uniqueWorldSamples());
        assertEquals(0, world.reads);
    }

    private static ShelterBlueprint blueprint() {
        return ShelterAnalyzer.analyze(ShelterAnalyzerTest.smallHouse())
                .blueprint()
                .orElseThrow();
    }

    private static FakeWorld intactWorld(
            ShelterBlueprint blueprint, int interiorBlockLight) {
        FakeWorld world = new FakeWorld();
        for (ShelterBlueprint.ShellCell cell : blueprint.shellCells()) {
            String id = cell.expectedBlockId().orElse("minecraft:oak_planks");
            world.blocks.put(cell.position(), sample(
                    id, false, true,
                    false, false, false, interiorBlockLight));
        }
        for (ShelterBlueprint.Doorway door : blueprint.doors()) {
            world.blocks.put(door.lower(), sample(
                    door.expectedBlockId(), false, false,
                    false, true, false, interiorBlockLight));
            world.blocks.put(door.lower().above(), sample(
                    door.expectedBlockId(), false, false,
                    false, true, false, interiorBlockLight));
        }
        for (BlockPos stance : blueprint.interiorStances()) {
            world.blocks.put(stance, sample(
                    "minecraft:air", true, false,
                    false, false, false, interiorBlockLight));
            world.blocks.put(stance.above(), sample(
                    "minecraft:air", true, false,
                    false, false, false, interiorBlockLight));
            world.blocks.put(stance.below(), sample(
                    "minecraft:stone", false, true,
                    false, false, false, interiorBlockLight));
        }
        return world;
    }

    private static ShelterWorldSampler.BlockSample sample(
            String blockId,
            boolean occupiable,
            boolean sturdyTop,
            boolean hazardous,
            boolean door,
            boolean open,
            int light) {
        return new ShelterWorldSampler.BlockSample(
                true,
                blockId,
                occupiable,
                sturdyTop,
                hazardous,
                door,
                open,
                light,
                0);
    }

    private static final class FakeWorld implements ShelterWorldSampler {
        private final Map<BlockPos, BlockSample> blocks = new HashMap<>();
        private int reads;
        private String dimensionId = "minecraft:overworld";

        @Override
        public String dimensionId() {
            return dimensionId;
        }

        @Override
        public BlockSample sample(BlockPos position) {
            reads++;
            return blocks.getOrDefault(
                    position,
                    ShelterVerifierTest.sample(
                            "minecraft:air",
                            true,
                            false,
                            false,
                            false,
                            false,
                            0));
        }
    }
}
