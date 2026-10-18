package com.agentic.orchestrator.model.live;

import com.agentic.orchestrator.model.ModelRequest;
import com.agentic.orchestrator.model.ModelResponse;
import com.agentic.orchestrator.model.live.OpenAiCompatibleModelGateway.TokenParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The OpenAI-compatible gateway against a local stub server that speaks the Chat Completions protocol:
 * what is sent, how the answer, usage and truncation are read, and how errors surface.
 */
class OpenAiCompatibleModelGatewayTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<Recorded> received = new ArrayList<>();
    private final Deque<int[]> statuses = new ArrayDeque<>();
    private final Deque<String> bodies = new ArrayDeque<>();

    private record Recorded(String path, String authorization, JsonNode body) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Recorded(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), JSON.readTree(body)));
            int status = statuses.isEmpty() ? 200 : statuses.poll()[0];
            byte[] out = (bodies.isEmpty() ? answer("ok", "stop") : bodies.poll()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
    }

    private OpenAiCompatibleModelGateway gateway(String key, TokenParam param) {
        return new OpenAiCompatibleModelGateway(baseUrl(), key, param, Duration.ZERO);
    }

    private static String answer(String content, String finishReason) {
        return """
                {"id":"x","model":"some-model-2026-10-01","choices":[{"index":0,
                 "message":{"role":"assistant","content":%s},"finish_reason":"%s"}],
                 "usage":{"prompt_tokens":120,"completion_tokens":34,"total_tokens":154}}
                """.formatted(JSON.valueToTree(content).toString(), finishReason);
    }

    private static ModelRequest request(Map<String, String> params) {
        return new ModelRequest("any-model", "You are a planner.", "Plan this.", params);
    }

    @Test
    void sendsModelMessagesAndOneOutputCapAndNoTemperature() {
        ModelResponse r = gateway("test-key", TokenParam.MAX_TOKENS).complete(request(Map.of("maxTokens", "16000")));

        Recorded call = received.get(0);
        assertThat(call.path()).isEqualTo("/v1/chat/completions");
        assertThat(call.authorization()).isEqualTo("Bearer test-key");
        assertThat(call.body().path("model").asText()).isEqualTo("any-model");
        assertThat(call.body().path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(call.body().path("messages").get(0).path("content").asText()).isEqualTo("You are a planner.");
        assertThat(call.body().path("messages").get(1).path("role").asText()).isEqualTo("user");
        assertThat(call.body().path("max_tokens").asInt()).isEqualTo(16000);
        assertThat(call.body().has("max_completion_tokens")).isFalse();
        assertThat(call.body().has("temperature")).isFalse();

        assertThat(r.text()).isEqualTo("ok");
        assertThat(r.model()).isEqualTo("any-model");
        assertThat(r.inputTokens()).isEqualTo(120);
        assertThat(r.outputTokens()).isEqualTo(34);
        assertThat(r.truncated()).isFalse();
    }

    @Test
    void canUseMaxCompletionTokensAndSendsTemperatureOnlyWhenConfigured() {
        gateway("k", TokenParam.MAX_COMPLETION_TOKENS)
                .complete(request(Map.of("maxTokens", "500", "temperature", "0.2")));

        JsonNode body = received.get(0).body();
        assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(500);
        assertThat(body.has("max_tokens")).isFalse();
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
    }

    @Test
    void localServersNeedNoKey() {
        gateway("", TokenParam.MAX_TOKENS).complete(request(Map.of()));
        assertThat(received.get(0).authorization()).isNull();
    }

    @Test
    void finishReasonLengthIsReportedAsTruncation() {
        bodies.add(answer("{\"files\": [", "length"));
        ModelResponse r = gateway("k", TokenParam.MAX_TOKENS).complete(request(Map.of()));
        assertThat(r.truncated()).isTrue();
        assertThat(r.text()).isEqualTo("{\"files\": [");
    }

    @Test
    void retriesOnceOnRateLimitThenSucceeds() {
        statuses.add(new int[]{429});
        bodies.add("{\"error\":{\"message\":\"slow down\"}}");
        ModelResponse r = gateway("k", TokenParam.MAX_TOKENS).complete(request(Map.of()));
        assertThat(received).hasSize(2);
        assertThat(r.text()).isEqualTo("ok");
    }

    @Test
    void errorsNameTheStatusAndNeverTheKey() {
        statuses.add(new int[]{401});
        bodies.add("{\"error\":{\"message\":\"invalid api key\"}}");
        assertThatThrownBy(() -> gateway("secret-key-value", TokenParam.MAX_TOKENS).complete(request(Map.of())))
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("invalid api key")
                .hasMessageNotContaining("secret-key-value");
        assertThat(received).hasSize(1);
    }

    @Test
    void aBaseUrlIsRequired() {
        assertThatThrownBy(() -> new OpenAiCompatibleModelGateway(" ", "k", TokenParam.MAX_TOKENS))
                .hasMessageContaining("AGENTIC_LLM_BASE_URL");
    }

    @Test
    void unknownTokenParamIsRejected() {
        assertThatThrownBy(() -> TokenParam.parse("max_output"))
                .hasMessageContaining("max_tokens or max_completion_tokens");
        assertThat(TokenParam.parse(null)).isEqualTo(TokenParam.MAX_TOKENS);
        assertThat(TokenParam.parse("MAX_COMPLETION_TOKENS")).isEqualTo(TokenParam.MAX_COMPLETION_TOKENS);
    }
}
