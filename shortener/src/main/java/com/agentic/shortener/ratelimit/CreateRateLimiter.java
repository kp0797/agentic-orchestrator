package com.agentic.shortener.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Token bucket per client for link creation. In-memory, so the limit is per node; a shared limit
 * across nodes would move the buckets to Redis (bucket4j supports that) and is listed as follow-up.
 */
public final class CreateRateLimiter {
    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
    }

    private final int perMinute;
    private final int maxClients;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public CreateRateLimiter(int perMinute, int maxClients) {
        this.perMinute = perMinute;
        this.maxClients = maxClients;
    }

    public Decision tryAcquire(String clientKey) {
        if (buckets.size() >= maxClients && !buckets.containsKey(clientKey)) {
            evictIdle();
            if (buckets.size() >= maxClients) {
                // Every tracked client is mid-limit: forget them all rather than grow without bound.
                // This briefly loosens the limit, but only for a client spread over this many IPs,
                // which a per-IP limit cannot stop anyway.
                buckets.clear();
            }
        }
        Bucket bucket = buckets.computeIfAbsent(clientKey, k -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new Decision(true, probe.getRemainingTokens(), 0);
        }
        long seconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds()
                + (probe.getNanosToWaitForRefill() % 1_000_000_000L == 0 ? 0 : 1));
        return new Decision(false, 0, seconds);
    }

    /** Drops buckets that are full again: those clients are not being limited, so nothing is lost. */
    void evictIdle() {
        buckets.entrySet().removeIf(e -> e.getValue().getAvailableTokens() >= perMinute);
    }

    int trackedClients() {
        return buckets.size();
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)).build())
                .build();
    }
}
