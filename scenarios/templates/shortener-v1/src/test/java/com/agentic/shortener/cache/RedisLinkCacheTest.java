package com.agentic.shortener.cache;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RedisLinkCacheTest {
    private LettuceConnectionFactory factory;

    @Test
    void encodingRoundTripsIncludingPipesInTheUrl() {
        CachedLink link = new CachedLink(42, "https://example.com/a|b?c=d|e", Instant.parse("2026-12-01T10:15:30.123456Z"));
        assertThat(RedisLinkCache.decode(RedisLinkCache.encode(link))).isEqualTo(link);
        CachedLink noExpiry = new CachedLink(7, "https://example.com", null);
        assertThat(RedisLinkCache.decode(RedisLinkCache.encode(noExpiry))).isEqualTo(noExpiry);
    }

    @Test
    void unreachableRedisDegradesToMissesAndOpensTheBreaker() {
        // Nothing listens on port 1: every call fails fast with "connection refused".
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).build());
        factory.afterPropertiesSet();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RedisLinkCache cache = new RedisLinkCache(new StringRedisTemplate(factory), Duration.ofHours(1), meters, Clock.systemUTC());

        for (int i = 0; i < 6; i++) {
            assertThat(cache.get("abc1234")).isEqualTo(Optional.empty());
            cache.put("abc1234", new CachedLink(1, "https://example.com", null));
        }

        assertThat(cache.breakerState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(meters.counter("shortener.cache.requests", "result", "error").count()).isGreaterThanOrEqualTo(6);
        // Once open, calls are refused immediately instead of waiting on Redis.
        long start = System.nanoTime();
        assertThat(cache.get("abc1234")).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(50));
    }

    @AfterEach
    void close() {
        if (factory != null) {
            factory.destroy();
        }
    }
}
