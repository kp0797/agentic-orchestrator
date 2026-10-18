package com.agentic.orchestrator.model;

import com.agentic.orchestrator.agents.AgentSupport;
import com.agentic.orchestrator.agents.PromptLibrary;
import com.agentic.orchestrator.agents.RunContext;
import com.agentic.orchestrator.engine.ExecutorFailure;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TruncationTest {

    /** The live design node hit its cap three times; the feedback must name that, not "invalid JSON". */
    @Test
    void aCutOffAnswerFailsWithActionableFeedbackAndStillCountsItsTokens() {
        ModelGateway capped = r -> new ModelResponse("{\"summary\": \"half an ans", r.model(), 8000, 16000, true);
        AgentSupport support = new AgentSupport(capped, ModelSettings.defaults(), new PromptLibrary(),
                new RunContext.Store());
        assertThatThrownBy(() -> support.call("architect", "requirements", Map.of("requirement", "x", "feedback", "")))
                .isInstanceOfSatisfying(ExecutorFailure.class, e -> {
                    assertThat(e.getMessage()).contains("cut off").contains("32000-token output limit")
                            .contains("Return a smaller answer");
                    assertThat(e.tokensUsed()).isEqualTo(24000);
                    assertThat(e.costUsd()).isPositive();
                });
    }

    @Test
    void truncationSurvivesRecordAndReplay() {
        Cassette c = new Cassette();
        ModelGateway rec = new RecordingModelGateway(r -> new ModelResponse("cut", r.model(), 1, 2, true), c);
        ModelRequest req = new ModelRequest("claude-opus-5-5", "s", "p", Map.of());
        rec.complete(req);
        assertThat(c.all().get(0).stopReason()).isEqualTo("max_tokens");
        assertThat(new ReplayModelGateway(c).complete(req).truncated()).isTrue();
    }
}
