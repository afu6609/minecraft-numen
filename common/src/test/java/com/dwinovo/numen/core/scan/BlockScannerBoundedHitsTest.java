package com.dwinovo.numen.core.scan;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockScannerBoundedHitsTest {

    @Test
    void retainsOnlyTheNearestHitsUnderAnAbundantTargetFlood() {
        BlockScanner.BoundedHits hits = new BlockScanner.BoundedHits(64);

        for (int distance = 999; distance >= 0; distance--) {
            hits.offer(new BlockScanner.Hit(
                    new BlockPos(distance, 0, 0),
                    null,
                    distance));
        }

        List<BlockScanner.Hit> result = hits.sorted();
        assertTrue(hits.full());
        assertEquals(64, result.size());
        assertEquals(0.0, result.get(0).distance());
        assertEquals(63.0, result.get(63).distance());
    }
}
