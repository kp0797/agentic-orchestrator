package com.agentic.orchestrator.model.live;

import com.agentic.orchestrator.model.ModelGateway;
import com.agentic.orchestrator.model.ModelRequest;
import com.agentic.orchestrator.model.ModelResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Live calls to any server that speaks the OpenAI Chat Completions protocol (provider
 * {@code openai-compatible}): OpenAI itself, and servers that expose the same API, such as Ollama, vLLM,
 * LM Studio or an API gateway. Only the base URL, key and model ids change between them.
 *
 * <p>Deliberately small and explicit rather than a vendor SDK: the request carries exactly the model,
 * the system and user messages and one output-cap parameter; nothing else is sent unless configured
 * (no default temperature). One retry on 429 and 5xx; the engine owns every other retry.
 */
public final class OpenAiCompatibleModelGateway implements ModelGateway {

    /** Name of the output-cap field: {@code max_tokens} (most servers) or {@code max_completion_tokens}. */
    public enum TokenParam {
        MAX_TOKENS("max_tokens"), MAX_COMPLETION_TOKENS("max_completion_tokens");

        final String field;

        TokenParam(String field) {
            this.field = field;
        }

        public static TokenParam parse(String value) {
            if (value == null || value.isBlank()) {
                return MAX_TOKENS;
            }
            for (TokenParam p : values()) {
                if (p.field.equalsIgnoreCase(value.strip())) {
                    return p;
                }
            }
            throw new IllegalArgumentException("unknown token parameter '" + value
                    + "': use max_tokens or max_completion_tokens");
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI endpoint;
    private final String apiKey;
    private final TokenParam tokenParam;
    private final Duration retryDelay;
    private final HttpClient http;

    /**
     * @param baseUrl    the API root, for example {@code https://api.openai.com/v1} or
     *                   {@code http://localhost:11434/v1}; {@code /chat/completions} is appended
     * @param apiKey     sent as a bearer token; may be empty for local servers that need none
     * @param tokenParam which field carries the output cap
     */
    public OpenAiCompatibleModelGateway(String baseUrl, String apiKey, TokenParam tokenParam) {
        this(baseUrl, apiKey, tokenParam, Duration.ofSeconds(2));
    }

    OpenAiCompatibleModelGateway(String baseUrl, String apiKey, TokenParam tokenParam, Duration retryDelay) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("provider 'openai-compatible' needs AGENTIC_LLM_BASE_URL, "
                    + "for example https://api.openai.com/v1 or http://localhost:11434/v1");
        }
        String root = baseUrl.strip().replaceAll("/+$", "");
        this.endpoint = URI.create(root + "/chat/completions");
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.tokenParam = tokenParam == null ? TokenParam.MAX_TOKENS : tokenParam;
        this.retryDelay = retryDelay;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        String body = requestBody(request, tokenParam);
        HttpResponse<String> response = send(body);
        if ((response.statusCode() == 429 || response.statusCode() >= 500) && sleep()) {
            response = send(body);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("model call failed: HTTP " + response.statusCode() + " from "
                    + endpoint.getHost() + ": " + abbreviate(response.body()));
        }
        return parse(response.body(), request.model());
    }

    /** The JSON request: model, messages, one output-cap field, and a temperature only when configured. */
    static String requestBody(ModelRequest request, TokenParam tokenParam) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", request.model());
        ArrayNode messages = root.putArray("messages");
        if (!request.system().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.system());
        }
        messages.addObject().put("role", "user").put("content", request.prompt());
        root.put(tokenParam.field, Integer.parseInt(request.params().getOrDefault("maxTokens", "4096")));
        if (request.params().containsKey("temperature")) {
            root.put("temperature", Double.parseDouble(request.params().get("temperature")));
        }
        return root.toString();
    }

    /**
     * The answer is the first choice's message content. A finish reason of {@code length} means the
     * output cap cut the answer off; the agents report that with the fix instead of a JSON error.
     */
    static ModelResponse parse(String body, String requestedModel) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("model response is not JSON: " + abbreviate(body), e);
        }
        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new IllegalStateException("model response has no choices: " + abbreviate(body));
        }
        JsonNode content = choice.path("message").path("content");
        String text = content.isTextual() ? content.asText() : "";
        boolean truncated = "length".equalsIgnoreCase(choice.path("finish_reason").asText(""));
        JsonNode usage = root.path("usage");
        long in = usage.path("prompt_tokens").asLong(0);
        long out = usage.path("completion_tokens").asLong(0);
        // The configured id, not the server's (often a dated variant): prices and metrics are keyed by it.
        return new ModelResponse(text, requestedModel, in, out, truncated);
    }

    private HttpResponse<String> send(String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (!apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("model call to " + endpoint.getHost() + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("model call interrupted", e);
        }
    }

    private boolean sleep() {
        try {
            Thread.sleep(retryDelay.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }
}
