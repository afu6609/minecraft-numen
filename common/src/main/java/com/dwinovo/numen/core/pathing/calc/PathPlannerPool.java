package com.dwinovo.numen.core.pathing.calc;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The shared worker pool that runs A* searches off the server tick thread.
 * One pool for ALL companions — 100 companions don't spawn 100 threads. Each
 * {@link com.dwinovo.numen.core.pathing.exec.PlayerNav} submits its search here and polls the returned
 * future each tick.
 *
 * <p>Sizing for a four-core game server: two fixed planner workers leave CPU for the server tick,
 * networking and the OS. A small bounded queue absorbs ordinary replan bursts without allowing stale
 * searches or worker threads to grow without limit. Threads are daemon so they never hold up JVM
 * shutdown.
 *
 * <p>TODO (config): expose core/max thread counts as a server setting, defaulting to these
 * values, for operators tuning many-companion servers.
 */
public final class PathPlannerPool {

    private PathPlannerPool() {}

    private static final AtomicInteger COUNTER = new AtomicInteger();
    static final int WORKER_THREADS = 2;
    static final int QUEUE_CAPACITY = 8;

    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(
            WORKER_THREADS, WORKER_THREADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "numen-path-" + COUNTER.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /**
     * Run {@code task} on the planner pool; the result lands in the returned future.
     *
     * <p>If both workers and the bounded queue are full, rejection is represented by an already
     * exceptional future. In particular, submission never runs a search on or blocks the server tick
     * thread.
     */
    public static <T> CompletableFuture<T> submit(Supplier<T> task) {
        try {
            return CompletableFuture.supplyAsync(task, POOL);
        } catch (RejectedExecutionException rejected) {
            return CompletableFuture.failedFuture(rejected);
        }
    }
}
