package com.agentic.orchestrator.model;

import java.util.List;
import java.util.Map;

/**
 * Which model each agent uses and what it costs. Provider-neutral: a model is just an id the configured
 * provider understands. Values come from configuration only; changing a model changes every request
 * key, so its cassettes must be re-recorded. A model without a configured price is reported at $0.
 */
public record ModelSettings(Map<String, AgentModel> agents, AgentModel fallbackDefault, List<String> fallbackChain,
                            Map<String, Price> prices) {

    /**
     * @param maxTokens   output token cap per call
     * @param temperature null means "not sent": current models reject the parameter
     */
    public record AgentModel(String model, int maxTokens, Double temperature) {}

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

    /** Every agent (and the default) uses one model id; output caps are kept. Prices are unchanged. */
    public ModelSettings withEveryAgentUsing(String model) {
        Map<String, AgentModel> replaced = new java.util.HashMap<>();
        agents.forEach((agent, m) -> replaced.put(agent, new AgentModel(model, m.maxTokens(), m.temperature())));
        AgentModel def = new AgentModel(model, fallbackDefault.maxTokens(), fallbackDefault.temperature());
        return new ModelSettings(replaced, def, fallbackChain, prices);
    }

    /** Whether a price is configured for the model; without one, cost is reported as 0. */
    public boolean hasPrice(String model) {
        return prices.containsKey(model);
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
        AgentModel opusPlan = new AgentModel("claude-opus-5-5", 16000, null);
        AgentModel opusEdit = new AgentModel("claude-opus-5-5", 32000, null);
        AgentModel sonnetSpec = new AgentModel("claude-sonnet-5-5", 16000, null);
        AgentModel sonnetEdit = new AgentModel("claude-sonnet-5-5", 32000, null);
        AgentModel haiku = new AgentModel("claude-haiku-5-5", 8192, null);
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
