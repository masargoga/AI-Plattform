package com.agenticpilot.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Runs slow work (model calls) off the UI thread. The signed-in user is captured when the task is submitted and set in the
 * worker thread, so services still see who is calling and write the right audit actor.
 */
@Component
public class BackgroundExecutor implements AutoCloseable {

    private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();

    public Future<?> submit(Runnable task) {
        SecurityContext captured = SecurityContextHolder.getContext();
        return delegate.submit(() -> {
            SecurityContextHolder.setContext(captured);
            try {
                task.run();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    @Override
    public void close() {
        delegate.shutdown();
    }
}
