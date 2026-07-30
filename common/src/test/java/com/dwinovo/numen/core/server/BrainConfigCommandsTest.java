package com.dwinovo.numen.core.server;

import com.dwinovo.numen.api.ServerBrainConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrainConfigCommandsTest {

    private static final ServerBrainConfiguration.Snapshot SNAPSHOT =
            new ServerBrainConfiguration.Snapshot(
                    "gpt-5.3-codex-spark",
                    "high",
                    "7",
                    List.of(
                            new ServerBrainConfiguration.CatalogEntry(
                                    "gpt-5.3-codex-spark",
                                    List.of("low", "medium", "high")),
                            new ServerBrainConfiguration.CatalogEntry(
                                    "gpt-5.6-luna",
                                    List.of("high"))),
                    1L);

    @Test
    void statusLabelsCachedStateAsLastConfirmed() {
        String status = BrainConfigCommands.formatStatus(SNAPSHOT);

        assertTrue(status.contains("上次确认"));
        assertTrue(status.contains("model=gpt-5.3-codex-spark"));
        assertTrue(status.contains("reasoning=high"));
        assertTrue(status.contains("revision=7"));
    }

    @Test
    void catalogPairsEachModelWithItsAllowedReasoning() {
        assertEquals(
                """
                server-brain 可用模型/思考强度（revision=7）：
                - gpt-5.3-codex-spark [low, medium, high]
                - gpt-5.6-luna [high]""",
                BrainConfigCommands.formatCatalog(SNAPSHOT));
    }

    @Test
    void emptyCacheNeverPretendsToKnowCurrentConfiguration() {
        assertTrue(
                BrainConfigCommands.formatStatus(null)
                        .contains("尚未收到"));
        assertTrue(
                BrainConfigCommands.formatCatalog(null)
                        .contains("尚未收到"));
    }

    @Test
    void firstExpirySweepCannotOverflowPastItsSentinel() {
        assertTrue(BrainConfigCommands.shouldSweep(
                0L, Long.MIN_VALUE));
        assertFalse(BrainConfigCommands.shouldSweep(19L, 0L));
        assertTrue(BrainConfigCommands.shouldSweep(20L, 0L));
        assertTrue(BrainConfigCommands.shouldSweep(5L, 100L));
    }
}
