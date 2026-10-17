package com.agentic.orchestrator.engine;

import java.time.Duration;

/**
 * @param maxParallel       cap on nodes running at once
 * @param pollInterval      how often the coordinator re-checks the kill switch and budget while waiting
 * @param rollbackOnFailure compensate every succeeded node, newest first, when the run fails
 */
public record EngineOptions(int maxParallel, Duration pollInterval, boolean rollbackOnFailure) {
    public static final EngineOptions DEFAULT = new EngineOptions(4, Duration.ofMillis(25), false);

    public EngineOptions {
        if (maxParallel < 1) {
            throw new IllegalArgumentException("maxParallel must be >= 1");
        }
    }

    public EngineOptions withRollbackOnFailure(boolean value) {
        return new EngineOptions(maxParallel, pollInterval, value);
    }

    public EngineOptions withMaxParallel(int value) {
        return new EngineOptions(value, pollInterval, rollbackOnFailure);
    }
}
