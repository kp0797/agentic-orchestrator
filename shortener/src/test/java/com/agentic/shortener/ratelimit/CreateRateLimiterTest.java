package com.agentic.shortener.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreateRateLimiterTest {

    @Test
    void allowsTheLimitThenRefusesWithARetryHint() {
        CreateRateLimiter limiter = new CreateRateLimiter(20, 1_000);
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire("1.2.3.4").allowed()).isTrue();
        }
        CreateRateLimiter.Decision refused = limiter.tryAcquire("1.2.3.4");
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void clientsAreLimitedSeparately() {
        CreateRateLimiter limiter = new CreateRateLimiter(1, 1_000);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void memoryIsBoundedByEvictingClientsWhoseBucketsAreFull() {
        CreateRateLimiter limiter = new CreateRateLimiter(5, 3);
        limiter.tryAcquire("busy");
        limiter.tryAcquire("busy");
        // Full buckets (idle clients) are evicted; the busy client keeps its state.
        limiter.evictIdle();
        assertThat(limiter.trackedClients()).isEqualTo(1);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("client-" + i);
        }
        assertThat(limiter.trackedClients()).isLessThanOrEqualTo(3);
    }
}
