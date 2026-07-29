package com.dwinovo.numen.core.scan;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A single shared background thread for off-thread world scans.
 * Mining's periodic ore rescan reads loaded
 * chunk section palettes; doing it here keeps the server tick free of the
 * {@code (2r)³}-cell sweep. One daemon thread (scans are fast and serialising
 * them across companions avoids piling work on the CPU); results are advisory
 * and re-validated on the main thread before anything is mined.
 */
public final class ScanExecutor {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "numen-scan");
        t.setDaemon(true);
        return t;
    });

    private ScanExecutor() {}

    public static <T> CompletableFuture<T> submit(Supplier<T> task) {
        AtomicReference<Future<?>> backing = new AtomicReference<>();
        CompletableFuture<T> result = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                Future<?> submitted = backing.get();
                if (cancelled && submitted != null) {
                    submitted.cancel(mayInterruptIfRunning);
                }
                return cancelled;
            }
        };
        Future<?> submitted = EXEC.submit(() -> {
            if (result.isCancelled()) return;
            try {
                result.complete(task.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        backing.set(submitted);
        if (result.isCancelled()) submitted.cancel(true);
        return result;
    }
}
