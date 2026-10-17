package com.agentic.orchestrator.model;

import java.util.List;
import java.util.Map;

/**
 * Which model each agent uses and what it costs. Values come from configuration only; changing a
 * model changes every request key, so its cassettes must be re-recorded.
 */
public record ModelSettings(Map<String, AgentModel> agents, AgentModel fallbackDefault, List<String> fallbackChain,
                            Map<String, Price> prices) {

    /** @param maxTokens output token cap per call */
    public record AgentModel(String model, int maxTokens, double temperature) {}

    /** USD per million tokens. */
    public record Price(double inputPerMTok, double outputPerMTok) {}

    public ModelSettings {
        agents = Map.copyOf(agents);
        fallbackChain = List.copyOf(fallbackChain == null ? List.of() : fallbackChain);
        prices = Map.copyOf(prices);
    }

    public AgentModel forAgent(String agent) {
        return agents.getOrDefault(agent, fallbackDefault);
    }

    public double cost(String model, long inputTokens, long outputTokens) {
        Price p = prices.get(model);
        if (p == null) {
            return 0.0;
        }
        return inputTokens / 1_000_000.0 * p.inputPerMTok() + outputTokens / 1_000_000.0 * p.outputPerMTok();
    }

    /**
     * Must stay identical to {@code agentic.models} in application.yml: the settings are part of every
     * request key, so any difference makes recorded cassettes miss (ModelSettingsTest checks this).
     * List prices as of October 2026.
     */
    public static ModelSettings defaults() {
        AgentModel opusPlan = new AgentModel("claude-opus-5-5", 8192, 0.0);
        AgentModel opusEdit = new AgentModel("claude-opus-5-5", 16000, 0.0);
        AgentModel sonnetSpec = new AgentModel("claude-sonnet-5-5", 8192, 0.0);
        AgentModel sonnetEdit = new AgentModel("claude-sonnet-5-5", 16000, 0.0);
        AgentModel haiku = new AgentModel("claude-haiku-5-5", 4096, 0.0);
        return new ModelSettings(
                Map.of("planner", opusPlan, "architect", opusEdit, "coder", opusEdit,
                        "requirements", sonnetSpec, "tester", sonnetEdit, "docs", haiku),
                sonnetSpec,
                List.of("claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1"),
                Map.of("claude-haiku-5-5", new Price(0.10, 0.50),
                        "claude-sonnet-5-5", new Price(2.0, 10.0),
                        "claude-opus-5-5", new Price(4.0, 20.0),
                        "claude-fable-5-1", new Price(10.0, 50.0)));
    }
}
