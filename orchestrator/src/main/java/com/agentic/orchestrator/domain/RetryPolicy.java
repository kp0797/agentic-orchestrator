package com.agentic.orchestrator.domain;

import java.time.Duration;
import java.util.Objects;

/** Bounded retry with exponential backoff, applied per executor in the fallback chain. */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(3, Duration.ofMillis(200), 2.0, Duration.ofSeconds(5));
    public static final RetryPolicy NO_RETRY = new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO);

    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 10, was " + maxAttempts);
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0");
        }
    }

    public static RetryPolicy attempts(int n) {
        return new RetryPolicy(n, DEFAULT.initialBackoff, DEFAULT.multiplier, DEFAULT.maxBackoff);
    }

    /** Backoff before the given attempt number (1-based). The first attempt never waits. */
    public Duration backoffBefore(int attemptWithinExecutor) {
        if (attemptWithinExecutor <= 1 || initialBackoff.isZero()) {
            return Duration.ZERO;
        }
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, attemptWithinExecutor - 2);
        long capped = (long) Math.min(millis, maxBackoff.toMillis());
        return Duration.ofMillis(capped);
    }
}
