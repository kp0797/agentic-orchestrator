package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.engine.NodeContext;
import com.agentic.orchestrator.model.ModelGateway;
import com.agentic.orchestrator.model.ModelRequest;
import com.agentic.orchestrator.model.ModelResponse;
import com.agentic.orchestrator.model.ModelSettings;

import java.util.Map;

/** Shared plumbing: render a versioned prompt, call the agent's configured model, account for cost. */
public final class AgentSupport {
    public record Call(ModelResponse response, String model, String promptHash, String promptId, double costUsd) {}

    private final ModelGateway gateway;
    private final ModelSettings settings;
    private final PromptLibrary prompts;
    private final RunContext.Store contexts;

    public AgentSupport(ModelGateway gateway, ModelSettings settings, PromptLibrary prompts, RunContext.Store contexts) {
        this.gateway = gateway;
        this.settings = settings;
        this.prompts = prompts;
        this.contexts = contexts;
    }

    public RunContext.Store contexts() {
        return contexts;
    }

    public Call call(String agentKey, String promptName, Map<String, String> vars) {
        PromptLibrary.Rendered rendered = prompts.render(promptName, vars);
        ModelSettings.AgentModel m = settings.forAgent(agentKey);
        Map<String, String> params = new java.util.HashMap<>(Map.of(
                "maxTokens", String.valueOf(m.maxTokens()),
                "prompt", rendered.template().id()));
        if (m.temperature() != null) {
            params.put("temperature", String.valueOf(m.temperature()));
        }
        ModelRequest request = new ModelRequest(m.model(), rendered.system(), rendered.user(), params);
        ModelResponse response = gateway.complete(request);
        double cost = settings.cost(response.model(), response.inputTokens(), response.outputTokens());
        if (response.truncated()) {
            // Name the real problem: a cut-off answer would otherwise surface as "invalid JSON".
            throw new com.agentic.orchestrator.engine.ExecutorFailure("the answer was cut off at the "
                    + m.maxTokens() + "-token output limit (stop reason max_tokens). Return a smaller answer: "
                    + "fewer and shorter files, minimal comments, and nothing outside the JSON object.",
                    response.totalTokens(), cost, null);
        }
        return new Call(response, response.model(), rendered.hash(), rendered.template().id(), cost);
    }

    /** Previous failure, phrased as a repair instruction. Empty on the first attempt. */
    public static String feedback(NodeContext ctx) {
        return ctx.feedback()
                .map(f -> "Your previous attempt was rejected. Fix exactly this and keep everything else that was"
                        + " right:\n" + f)
                .orElse("");
    }
}
