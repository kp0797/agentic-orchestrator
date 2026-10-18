package com.agentic.orchestrator.model.live;

import com.agentic.orchestrator.model.live.LiveGateways.Provider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Provider choice and key lookup. No network: constructing a gateway makes no call. */
class LiveGatewaysTest {

    @Test
    void anthropicIsTheDefaultProvider() {
        assertThat(Provider.parse(null)).isEqualTo(Provider.ANTHROPIC);
        assertThat(LiveGateways.create("", "", "", Map.of("ANTHROPIC_API_KEY", "k")))
                .isInstanceOf(AnthropicModelGateway.class);
    }

    @Test
    void openAiCompatibleProviderNeedsOnlyABaseUrl() {
        assertThat(LiveGateways.create("openai-compatible", "http://localhost:11434/v1", "max_tokens", Map.of()))
                .isInstanceOf(OpenAiCompatibleModelGateway.class);
        assertThat(LiveGateways.create(" OpenAI-Compatible ", "https://api.example.test/v1", null, Map.of()))
                .isInstanceOf(OpenAiCompatibleModelGateway.class);
    }

    @Test
    void theGenericKeyWinsOverTheProviderConvention() {
        Map<String, String> env = Map.of("AGENTIC_LLM_API_KEY", "generic", "OPENAI_API_KEY", "conventional");
        assertThat(LiveGateways.apiKey(Provider.OPENAI_COMPATIBLE, env)).isEqualTo("generic");
        assertThat(LiveGateways.apiKey(Provider.OPENAI_COMPATIBLE, Map.of("OPENAI_API_KEY", "conventional")))
                .isEqualTo("conventional");
        assertThat(LiveGateways.apiKey(Provider.ANTHROPIC, Map.of("OPENAI_API_KEY", "other"))).isEmpty();
    }

    @Test
    void missingKeyAndUnknownProviderFailWithTheFix() {
        assertThatThrownBy(() -> LiveGateways.create("anthropic", "", "", Map.of()))
                .hasMessageContaining("AGENTIC_LLM_API_KEY").hasMessageContaining("ANTHROPIC_API_KEY");
        assertThatThrownBy(() -> LiveGateways.create("openai-compatible", "", "", Map.of()))
                .hasMessageContaining("AGENTIC_LLM_BASE_URL");
        assertThatThrownBy(() -> LiveGateways.create("gemini", "", "", Map.of()))
                .hasMessageContaining("anthropic or openai-compatible");
    }
}
