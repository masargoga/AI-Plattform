package com.agenticpilot.api;

import com.agenticpilot.common.RateLimiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void bucketRefillsOverTimeAndKeysAreIndependent() {
        AtomicLong now = new AtomicLong();
        RateLimiter limiter = new RateLimiter(6, now::get);
        for (int i = 0; i < 6; i++) {
            assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        }
        var denied = limiter.tryAcquire("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(10);
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();

        now.addAndGet(10_000_000_000L); // ten seconds refill one token at 6 per minute
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();

        now.addAndGet(3_600_000_000_000L); // an hour: the bucket is full but never above the limit
        for (int i = 0; i < 6; i++) {
            assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void generatedKeysHaveTheExpectedShapeAndHashesAreStable() {
        String prefix = ApiKeyService.randomPrefix();
        assertThat(prefix).hasSize(ApiKeyService.PREFIX_LENGTH).matches("[a-z2-9]+");
        String secret = ApiKeyService.randomSecret();
        assertThat(secret).hasSize(43);
        assertThat(ApiKeyService.hash(secret)).isEqualTo(ApiKeyService.hash(secret)).hasSize(64).isNotEqualTo(ApiKeyService.hash(secret + "x"));
        assertThat(ApiKeyService.randomSecret()).isNotEqualTo(secret);
    }
}
