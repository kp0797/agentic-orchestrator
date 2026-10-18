package com.agentic.shortener.cache;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Redis-backed {@link LinkCache}. Every call goes through a circuit breaker: when Redis is down,
 * the breaker opens after a few failures and redirects go straight to the database without waiting
 * on Redis timeouts; it probes Redis again after 10 seconds.
 */
public final class RedisLinkCache implements LinkCache {
    private static final Logger log = LoggerFactory.getLogger(RedisLinkCache.class);
    private static final String PREFIX = "link:";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Clock clock;
    private final CircuitBreaker breaker;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    public RedisLinkCache(StringRedisTemplate redis, Duration ttl, MeterRegistry meters, Clock clock) {
        this.redis = redis;
        this.ttl = ttl;
        this.clock = clock;
        this.breaker = CircuitBreaker.of("link-cache", CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build());
        this.hits = Counter.builder("shortener.cache.requests").tag("result", "hit").register(meters);
        this.misses = Counter.builder("shortener.cache.requests").tag("result", "miss").register(meters);
        this.errors = Counter.builder("shortener.cache.requests").tag("result", "error").register(meters);
    }

    @Override
    public Optional<CachedLink> get(String code) {
        try {
            String value = breaker.executeSupplier(() -> redis.opsForValue().get(PREFIX + code));
            if (value == null) {
                misses.increment();
                return Optional.empty();
            }
            hits.increment();
            return Optional.of(decode(value));
        } catch (RuntimeException e) {
            errors.increment();
            log.debug("cache read failed for {}: {}", code, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(String code, CachedLink link) {
        Duration entryTtl = ttl;
        if (link.expiresAt() != null) {
            Duration untilExpiry = Duration.between(clock.instant(), link.expiresAt());
            if (untilExpiry.isNegative() || untilExpiry.isZero()) {
                return;
            }
            entryTtl = untilExpiry.compareTo(ttl) < 0 ? untilExpiry : ttl;
        }
        Duration finalTtl = entryTtl;
        try {
            breaker.executeRunnable(() -> redis.opsForValue().set(PREFIX + code, encode(link), finalTtl));
        } catch (RuntimeException e) {
            errors.increment();
            log.debug("cache write failed for {}: {}", code, e.toString());
        }
    }

    public CircuitBreaker.State breakerState() {
        return breaker.getState();
    }

    static String encode(CachedLink link) {
        String expires = link.expiresAt() == null ? "-" : link.expiresAt().toString();
        return link.linkId() + "|" + expires + "|" + link.targetUrl();
    }

    static CachedLink decode(String value) {
        int first = value.indexOf('|');
        int second = value.indexOf('|', first + 1);
        long id = Long.parseLong(value.substring(0, first));
        String expires = value.substring(first + 1, second);
        Instant expiresAt = expires.equals("-") ? null : Instant.parse(expires);
        return new CachedLink(id, value.substring(second + 1), expiresAt);
    }
}
