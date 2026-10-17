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
        Map<String, Model> models,
        Map<String, Price> prices) {

    public record Model(String model, Integer maxTokens, Double temperature) {}

    public record Price(double inputPerMTok, double outputPerMTok) {}

    public AgenticProperties {
        mode = mode == null ? GatewayMode.REPLAY : mode;
        runsDir = runsDir == null ? "runs" : runsDir;
        policiesDir = policiesDir == null ? "policies" : policiesDir;
        scenariosDir = scenariosDir == null ? "scenarios" : scenariosDir;
        maxParallel = maxParallel <= 0 ? 4 : maxParallel;
        models = models == null ? Map.of() : Map.copyOf(models);
        prices = prices == null ? Map.of() : Map.copyOf(prices);
    }
}
