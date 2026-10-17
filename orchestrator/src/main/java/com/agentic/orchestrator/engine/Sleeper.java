package com.agentic.orchestrator.engine;

import java.time.Duration;

/** Injected so retry backoff is real in production and instant in tests. */
@FunctionalInterface
public interface Sleeper {
    Sleeper REAL = d -> { if (!d.isZero()) Thread.sleep(d); };
    Sleeper NONE = d -> { };

    void sleep(Duration duration) throws InterruptedException;
}
