package com.agentic.orchestrator.model.live;

import com.agentic.orchestrator.model.ModelGateway;
import com.agentic.orchestrator.model.ModelRequest;
import com.agentic.orchestrator.model.ModelResponse;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.support.RetryTemplate;

import java.util.List;

/**
 * Live Anthropic calls through Spring AI. Built by hand rather than by autoconfiguration, so the
 * application never needs or reads an API key unless live or record mode is requested.
 * Spring AI's own retries are kept to one extra attempt: the engine owns retry policy.
 */
public final class SpringAiModelGateway implements ModelGateway {
    private final AnthropicChatModel chatModel;

    public SpringAiModelGateway(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY is not set; live and record modes need it. "
                    + "Replay mode does not.");
        }
        AnthropicApi api = AnthropicApi.builder().apiKey(apiKey).build();
        this.chatModel = AnthropicChatModel.builder()
                .anthropicApi(api)
                .retryTemplate(RetryTemplate.builder().maxAttempts(2).exponentialBackoff(1000, 2, 8000).build())
                .build();
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(request.model())
                .maxTokens(Integer.parseInt(request.params().getOrDefault("maxTokens", "4096")))
                .temperature(Double.parseDouble(request.params().getOrDefault("temperature", "0")))
                .build();
        Prompt prompt = new Prompt(List.of(new SystemMessage(request.system()), new UserMessage(request.prompt())),
                options);
        ChatResponse response = chatModel.call(prompt);
        String text = response.getResult().getOutput().getText();
        Usage usage = response.getMetadata().getUsage();
        long in = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long out = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        return new ModelResponse(text == null ? "" : text, request.model(), in, out);
    }
}
