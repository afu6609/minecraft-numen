package com.dwinovo.numen.core.pathing.calc;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathPlannerPoolTest {

    @Test
    void saturationReturnsExceptionalFutureWithoutBlockingSubmitter() {
        CountDownLatch workersStarted = new CountDownLatch(PathPlannerPool.WORKER_THREADS);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        List<CompletableFuture<Void>> accepted = new ArrayList<>();

        try {
            for (int i = 0; i < PathPlannerPool.WORKER_THREADS; i++) {
                accepted.add(PathPlannerPool.submit(() -> {
                    workersStarted.countDown();
                    try {
                        releaseWorkers.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }));
            }
            assertTrue(workersStarted.await(1, TimeUnit.SECONDS), "planner workers did not start");

            for (int i = 0; i < PathPlannerPool.QUEUE_CAPACITY; i++) {
                accepted.add(PathPlannerPool.submit(() -> null));
            }

            CompletableFuture<Void> rejected = assertTimeoutPreemptively(
                    Duration.ofMillis(250), () -> PathPlannerPool.submit(() -> null));

            assertTrue(rejected.isCompletedExceptionally());
            CompletionException failure =
                    org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class, rejected::join);
            assertInstanceOf(RejectedExecutionException.class, failure.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        } finally {
            releaseWorkers.countDown();
            accepted.forEach(future -> {
                try {
                    future.get(1, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Cleanup only; assertions above describe the behavior under test.
                }
            });
        }

        assertEquals(2, PathPlannerPool.WORKER_THREADS);
        assertEquals(8, PathPlannerPool.QUEUE_CAPACITY);
    }
}
