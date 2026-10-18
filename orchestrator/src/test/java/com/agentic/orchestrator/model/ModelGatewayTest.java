package com.agentic.orchestrator.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelGatewayTest {
    private final ModelRequest req = new ModelRequest("claude-opus-5-5", "you are a coder", "write LinkService",
            Map.of("temperature", "0"));

    @Test
    void recordThenReplayGivesIdenticalResponsesInOrder(@TempDir Path dir) {
        int[] n = {0};
        Cassette cassette = new Cassette();
        ModelGateway recording = new RecordingModelGateway(new FakeModelGateway(r -> "answer " + (++n[0])), cassette);
        recording.complete(req);
        recording.complete(req); // same prompt twice, e.g. a retry: both recorded, in order

        Path file = dir.resolve("cassette.jsonl");
        JsonlCassetteFile.write(cassette, file);
        ModelGateway replay = new ReplayModelGateway(JsonlCassetteFile.read(file));

        ModelResponse first = replay.complete(req);
        assertThat(first.text()).isEqualTo("answer 1");
        assertThat(replay.complete(req).text()).isEqualTo("answer 2");
        // A replay calls no model: this run used no tokens and spent nothing, whatever the recording did.
        assertThat(first.totalTokens()).isZero();
        assertThat(cassette.all().get(0).inputTokens() + cassette.all().get(0).outputTokens()).isPositive();
    }

    @Test
    void cassetteMissFailsLoudlyAndNeverCallsLive() {
        ModelGateway replay = new ReplayModelGateway(new Cassette());
        assertThatThrownBy(() -> replay.complete(req))
                .isInstanceOf(CassetteMissException.class)
                .hasMessageContaining("re-record this scenario");
    }

    @Test
    void anyChangeToModelPromptOrParamsChangesTheKey() {
        assertThat(req.key())
                .isNotEqualTo(new ModelRequest("claude-sonnet-5-5", req.system(), req.prompt(), req.params()).key())
                .isNotEqualTo(new ModelRequest(req.model(), req.system(), req.prompt() + " ", req.params()).key())
                .isNotEqualTo(new ModelRequest(req.model(), req.system(), req.prompt(), Map.of("temperature", "1")).key());
    }
}
