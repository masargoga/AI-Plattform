package com.agenticpilot.api;

import java.util.concurrent.ConcurrentHashMap;

/** A token bucket per key, in memory. With several replicas each replica has its own buckets. */
public final class RateLimiter {

    /** The outcome of one attempt: whether it may go ahead, how many are left and when to retry. */
    public record Result(boolean allowed, int limit, int remaining, long retryAfterSeconds) {
    }

    private static final class Bucket {
        double tokens;
        long lastNanos;
    }

    private final int perMinute;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;

    public RateLimiter(int perMinute) {
        this(perMinute, System::nanoTime);
    }

    RateLimiter(int perMinute, java.util.function.LongSupplier clock) {
        this.perMinute = Math.max(1, perMinute);
        this.clock = clock;
    }

    public Result tryAcquire(String key) {
        long now = clock.getAsLong();
        if (buckets.size() > 10_000) {
            buckets.entrySet().removeIf(e -> now - e.getValue().lastNanos > 120_000_000_000L);
        }
        Bucket b = buckets.computeIfAbsent(key, k -> {
            Bucket nb = new Bucket();
            nb.tokens = perMinute;
            nb.lastNanos = now;
            return nb;
        });
        synchronized (b) {
            double refill = (now - b.lastNanos) / 60_000_000_000.0 * perMinute;
            b.tokens = Math.min(perMinute, b.tokens + refill);
            b.lastNanos = now;
            if (b.tokens >= 1) {
                b.tokens -= 1;
                return new Result(true, perMinute, (int) Math.floor(b.tokens), 0);
            }
            long wait = (long) Math.ceil((1 - b.tokens) / perMinute * 60);
            return new Result(false, perMinute, 0, Math.max(1, wait));
        }
    }
}
