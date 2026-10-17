package com.agentic.orchestrator.domain;

import java.time.Duration;

/** Hard limits for a run. Exceeding any of them triggers a safe-stop, never a silent overrun. */
public record RunBudget(Duration maxWallTime, long maxTokens, double maxCostUsd) {
    /** Generous limits for tests and demos; real runs always set explicit ones. */
    public static final RunBudget UNLIMITED = new RunBudget(Duration.ofHours(1), 10_000_000, 1_000.0);

    public RunBudget {
        if (maxWallTime == null || maxWallTime.isNegative() || maxWallTime.isZero()) {
            throw new IllegalArgumentException("maxWallTime must be positive");
        }
        if (maxTokens <= 0 || maxCostUsd <= 0) {
            throw new IllegalArgumentException("token and cost limits must be positive");
        }
    }
}
