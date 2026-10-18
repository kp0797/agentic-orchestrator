package com.agentic.orchestrator.model.live;

import com.agentic.orchestrator.model.ModelGateway;

import java.util.Locale;
import java.util.Map;

/**
 * Chooses the live gateway from configuration. Nothing else in the system knows which provider or
 * model is behind {@link ModelGateway}: agents, gates and the engine are unchanged by a switch.
 *
 * <p>The key is read from the environment only, never from a file: {@code AGENTIC_LLM_API_KEY} for any
 * provider, or the provider's conventional variable ({@code ANTHROPIC_API_KEY}, {@code OPENAI_API_KEY}).
 */
public final class LiveGateways {
    private LiveGateways() {}

    public enum Provider {
        ANTHROPIC("anthropic", "ANTHROPIC_API_KEY"),
        OPENAI_COMPATIBLE("openai-compatible", "OPENAI_API_KEY");

        public final String id;
        final String conventionalKeyVariable;

        Provider(String id, String conventionalKeyVariable) {
            this.id = id;
            this.conventionalKeyVariable = conventionalKeyVariable;
        }

        public static Provider parse(String value) {
            String v = value == null || value.isBlank() ? "anthropic" : value.strip().toLowerCase(Locale.ROOT);
            for (Provider p : values()) {
                if (p.id.equals(v)) {
                    return p;
                }
            }
            throw new IllegalArgumentException("unknown model provider '" + value
                    + "': use anthropic or openai-compatible");
        }
    }

    /**
     * @param provider   {@code anthropic} or {@code openai-compatible}
     * @param baseUrl    API root for {@code openai-compatible}; ignored for {@code anthropic}
     * @param tokenParam output-cap field for {@code openai-compatible}: max_tokens or max_completion_tokens
     * @param env        the process environment (a map so tests can pass their own)
     */
    public static ModelGateway create(String provider, String baseUrl, String tokenParam, Map<String, String> env) {
        Provider p = Provider.parse(provider);
        String key = apiKey(p, env);
        return switch (p) {
            case ANTHROPIC -> new AnthropicModelGateway(key, env.get("ANTHROPIC_WORKSPACE_ID"));
            case OPENAI_COMPATIBLE -> new OpenAiCompatibleModelGateway(baseUrl, key,
                    OpenAiCompatibleModelGateway.TokenParam.parse(tokenParam));
        };
    }

    static String apiKey(Provider provider, Map<String, String> env) {
        String generic = env.get("AGENTIC_LLM_API_KEY");
        if (generic != null && !generic.isBlank()) {
            return generic;
        }
        return env.getOrDefault(provider.conventionalKeyVariable, "");
    }
}
