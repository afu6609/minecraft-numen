package com.dwinovo.numen.core.task.survival.shelter;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/**
 * Minimal read-only world sensor used by live shelter verification.
 *
 * <p>Implementations must not load chunks. The verifier memoizes samples, so
 * each absolute coordinate is read at most once per verification pass.</p>
 */
public interface ShelterWorldSampler {

    /** Registry id of the sampled level, for example {@code minecraft:overworld}. */
    String dimensionId();

    BlockSample sample(BlockPos position);

    record BlockSample(
            boolean loaded,
            String blockId,
            boolean occupiable,
            boolean sturdyTop,
            boolean hazardous,
            boolean door,
            boolean doorOpen,
            int blockLight,
            int skyLight) {

        public BlockSample {
            if (loaded) {
                Objects.requireNonNull(blockId, "blockId");
                checkLight(blockLight, "blockLight");
                checkLight(skyLight, "skyLight");
            } else {
                blockId = "";
                occupiable = false;
                sturdyTop = false;
                hazardous = false;
                door = false;
                doorOpen = false;
                blockLight = -1;
                skyLight = -1;
            }
            if (doorOpen && !door) {
                throw new IllegalArgumentException("only a door may be open");
            }
        }

        public static BlockSample unloaded() {
            return new BlockSample(
                    false, "", false, false, false,
                    false, false, -1, -1);
        }

        private static void checkLight(int light, String name) {
            if (light < 0 || light > 15) {
                throw new IllegalArgumentException(name + " must be in [0,15]");
            }
        }
    }
}
