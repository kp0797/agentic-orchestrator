package com.agentic.orchestrator.gates;

/**
 * A deterministic exit check. Gates must not call an LLM: a model may advise, but it never decides
 * whether work is accepted.
 */
public interface Gate {
    String id();

    GateResult evaluate(GateContext context);
}
