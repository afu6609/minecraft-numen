package com.dwinovo.numen.core.scan;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ScanExecutorCancellationTest {

    @Test
    void cancellationInterruptsTheBackingScan() {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CompletableFuture<Void> scan = ScanExecutor.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        });

        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertTrue(scan.cancel(true));
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        });
    }
}
