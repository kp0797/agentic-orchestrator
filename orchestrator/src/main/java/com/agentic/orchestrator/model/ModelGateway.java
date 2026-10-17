package com.agentic.orchestrator.model;

/**
 * The only way any agent reaches an LLM. Implementations: live (Spring AI, Day 2), recording,
 * replay and fake. Swapping provider or model never touches the engine, gates or agents.
 */
public interface ModelGateway {
    ModelResponse complete(ModelRequest request);
}
