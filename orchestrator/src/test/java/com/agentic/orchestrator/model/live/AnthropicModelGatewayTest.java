package com.agentic.orchestrator.model.live;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicModelGatewayTest {

    /** Reproduces the live response that came back as empty text: thinking first, answer second. */
    @Test
    void readsTheAnswerNotTheThinkingBlock() {
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("", Map.of("signature", "sig"))),
                new Generation(new AssistantMessage(null, Map.of("data", "redacted"))),
                new Generation(new AssistantMessage("{\"title\": \"URL Shortener\"}", Map.of()))));
        assertThat(AnthropicModelGateway.answerText(response)).isEqualTo("{\"title\": \"URL Shortener\"}");
    }

    @Test
    void thinkingTextNeverLeaksIntoTheAnswer() {
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("let me think {not json}", Map.of("signature", "s"))),
                new Generation(new AssistantMessage("{\"ok\": true}", Map.of()))));
        assertThat(AnthropicModelGateway.answerText(response)).isEqualTo("{\"ok\": true}");
    }
}
