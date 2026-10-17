package com.agentic.orchestrator.engine;

/**
 * An agent or deterministic step. Executors propose outputs; gates and humans decide. Each executor
 * declares how to undo its side effects, which is what makes saga-style rollback possible.
 */
public interface NodeExecutor {
    String key();

    NodeResult execute(NodeContext context) throws Exception;

    /** Undo side effects (revert commits, rebuild a scratch database). Default: nothing to undo. */
    default String compensate(CompensationContext context) throws Exception {
        return "no side effects to undo";
    }
}
