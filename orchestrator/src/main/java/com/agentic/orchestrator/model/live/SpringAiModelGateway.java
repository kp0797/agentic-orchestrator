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
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Live Anthropic calls through Spring AI. Built by hand rather than by autoconfiguration, so the
 * application never needs or reads an API key unless live or record mode is requested.
 * Spring AI's own retries are kept to one extra attempt: the engine owns retry policy.
 */
public final class SpringAiModelGateway implements ModelGateway {
    private final AnthropicChatModel chatModel;

    public SpringAiModelGateway(String apiKey) {
        this(apiKey, System.getenv("ANTHROPIC_WORKSPACE_ID"));
    }

    /**
     * @param workspaceId only needed for keys not scoped to a workspace; a workspace-scoped key
     *                    (recommended, so the workspace spend cap applies) needs none
     */
    public SpringAiModelGateway(String apiKey, String workspaceId) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY is not set; live and record modes need it. "
                    + "Replay mode does not.");
        }
        RestClient.Builder rest = RestClient.builder();
        if (workspaceId != null && !workspaceId.isBlank()) {
            rest.defaultHeader("anthropic-workspace-id", workspaceId.strip());
        }
        AnthropicApi api = AnthropicApi.builder().apiKey(apiKey).restClientBuilder(rest).build();
        this.chatModel = AnthropicChatModel.builder()
                .anthropicApi(api)
                // Explicit defaults: the builder's own defaults include a temperature, which current
                // models reject. Per-request options set the model and token cap.
                .defaultOptions(AnthropicChatOptions.builder().maxTokens(1024).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(2).exponentialBackoff(1000, 2, 8000).build())
                .build();
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder()
                .model(request.model())
                .maxTokens(Integer.parseInt(request.params().getOrDefault("maxTokens", "4096")));
        if (request.params().containsKey("temperature")) {
            builder.temperature(Double.parseDouble(request.params().get("temperature")));
        }
        AnthropicChatOptions options = builder.build();
        Prompt prompt = new Prompt(List.of(new SystemMessage(request.system()), new UserMessage(request.prompt())),
                options);
        ChatResponse response = chatModel.call(prompt);
        String text = answerText(response);
        Usage usage = response.getMetadata().getUsage();
        long in = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long out = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        boolean truncated = response.getResults().stream()
                .anyMatch(g -> "max_tokens".equals(g.getMetadata().getFinishReason()));
        return new ModelResponse(text, request.model(), in, out, truncated);
    }

    /**
     * The model's answer: the text blocks only. Spring AI maps every content block to its own
     * generation; thinking blocks carry a "signature" and redacted thinking carries "data". Reading
     * only the first generation returns the (often empty) thinking block instead of the answer.
     */
    static String answerText(ChatResponse response) {
        StringBuilder sb = new StringBuilder();
        for (Generation g : response.getResults()) {
            Map<String, Object> meta = g.getOutput().getMetadata();
            if (meta.containsKey("signature") || meta.containsKey("data")) {
                continue;
            }
            String t = g.getOutput().getText();
            if (t != null && !t.isBlank()) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(t);
            }
        }
        return sb.toString();
    }
}
