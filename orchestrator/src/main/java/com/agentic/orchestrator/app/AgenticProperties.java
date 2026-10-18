package com.agentic.orchestrator.app;

import com.agentic.orchestrator.model.GatewayMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Configuration under {@code agentic.*}. Model ids and prices live in application.yml, never in code.
 */
@ConfigurationProperties(prefix = "agentic")
public record AgenticProperties(
        GatewayMode mode,
        String runsDir,
        String policiesDir,
        String scenariosDir,
        boolean mavenOffline,
        int maxParallel,
        Llm llm,
        Map<String, Model> models,
        Map<String, Price> prices) {

    /**
     * Which provider serves live and record modes. The key is never configured here: it comes from the
     * environment only (see LiveGateways).
     *
     * @param provider   anthropic | openai-compatible
     * @param baseUrl    API root for openai-compatible servers
     * @param model      when set, every agent uses this model id (handy for a single local model)
     * @param tokenParam output-cap field for openai-compatible servers: max_tokens or max_completion_tokens
     */
    public record Llm(String provider, String baseUrl, String model, String tokenParam) {
        public Llm {
            provider = provider == null || provider.isBlank() ? "anthropic" : provider.strip();
            baseUrl = baseUrl == null ? "" : baseUrl.strip();
            model = model == null ? "" : model.strip();
            tokenParam = tokenParam == null || tokenParam.isBlank() ? "max_tokens" : tokenParam.strip();
        }
    }

    public record Model(String model, Integer maxTokens, Double temperature) {}

    public record Price(double inputPerMTok, double outputPerMTok) {}

    public AgenticProperties {
        mode = mode == null ? GatewayMode.REPLAY : mode;
        runsDir = runsDir == null ? "runs" : runsDir;
        policiesDir = policiesDir == null ? "policies" : policiesDir;
        scenariosDir = scenariosDir == null ? "scenarios" : scenariosDir;
        maxParallel = maxParallel <= 0 ? 4 : maxParallel;
        llm = llm == null ? new Llm(null, null, null, null) : llm;
        models = models == null ? Map.of() : Map.copyOf(models);
        prices = prices == null ? Map.of() : Map.copyOf(prices);
    }
}
