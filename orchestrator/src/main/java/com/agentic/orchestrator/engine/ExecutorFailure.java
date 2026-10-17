package com.agentic.orchestrator.engine;

/**
 * An executor failure that still consumed tokens. The engine records the usage before the failure,
 * so budgets and cost reports include failed attempts, not only successful ones.
 */
public class ExecutorFailure extends RuntimeException {
    private final long tokensUsed;
    private final double costUsd;

    public ExecutorFailure(String message, long tokensUsed, double costUsd, Throwable cause) {
        super(message, cause);
        this.tokensUsed = tokensUsed;
        this.costUsd = costUsd;
    }

    public long tokensUsed() { return tokensUsed; }
    public double costUsd() { return costUsd; }
}
