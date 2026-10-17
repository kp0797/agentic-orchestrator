package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.artifacts.InMemoryArtifactStore;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.engine.ApprovalHandler;
import com.agentic.orchestrator.engine.EngineOptions;
import com.agentic.orchestrator.engine.PlanStore;
import com.agentic.orchestrator.engine.RunOutcome;
import com.agentic.orchestrator.engine.Sleeper;
import com.agentic.orchestrator.events.InMemoryEventStore;
import com.agentic.orchestrator.gates.MavenGate;
import com.agentic.orchestrator.model.Cassette;
import com.agentic.orchestrator.model.CassetteMissException;
import com.agentic.orchestrator.model.GatewayMode;
import com.agentic.orchestrator.model.JsonlCassetteFile;
import com.agentic.orchestrator.model.ModelGateway;
import com.agentic.orchestrator.model.ModelGateways;
import com.agentic.orchestrator.model.ModelSettings;
import com.agentic.orchestrator.model.RecordingModelGateway;
import com.agentic.orchestrator.policy.PolicyLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Records a scenario, then replays it with no model at all and checks the replayed run reaches the
 * same outputs. Also writes {@code scenarios/pipeline-smoke/cassette.jsonl} when asked to with
 * {@code -Dsmoke.cassette.out=...}.
 */
class RecordReplayTest {
    @TempDir
    Path tmp;

    private Orchestrator orchestrator(Scenario scenario, Path runs, ModelGateway gateway) {
        Orchestrator.Settings settings = new Orchestrator.Settings(runs, PolicyLoader.loadDirectory(Path.of("../policies")),
                ModelSettings.defaults(), new MavenGate.Settings("mvn", true, System.getProperty("maven.repo.local"),
                Duration.ofMinutes(5)), EngineOptions.DEFAULT, Sleeper.NONE);
        return new Orchestrator(scenario, settings, gateway, new InMemoryEventStore(), new InMemoryArtifactStore(),
                new PlanStore.InMemory(), ApprovalHandler.autoApprove("record-replay-test"));
    }

    @Test
    void replayReproducesTheRecordedRunWithoutAModel() throws Exception {
        Scenario loaded = ScenarioLoader.load(Path.of("../scenarios/pipeline-smoke"));
        Path cassette = tmp.resolve("cassette.jsonl");
        Scenario scenario = new Scenario(loaded.name(), loaded.description(), loaded.requirement(), loaded.template(),
                cassette, loaded.budget(), loaded.faults());

        ScriptedModel scripted = new ScriptedModel(Map.of(
                "requirements", n -> ScriptedModel.SPEC, "planner", n -> ScriptedModel.PLAN,
                "architect", n -> ScriptedModel.DESIGN, "coder", n -> ScriptedModel.impl(true),
                "tester", n -> ScriptedModel.TESTS, "docs", n -> ScriptedModel.DOCS));
        Orchestrator recorded = orchestrator(scenario, tmp.resolve("rec"),
                new RecordingModelGateway(scripted, new Cassette(), cassette));
        RunOutcome first = recorded.drive();
        assertThat(first.status()).isEqualTo(RunStatus.SUCCEEDED);

        ModelGateway replay = ModelGateways.create(GatewayMode.REPLAY, cassette, () -> {
            throw new AssertionError("replay must never build a live gateway");
        });
        Orchestrator replayed = orchestrator(scenario, tmp.resolve("rep"), replay);
        RunOutcome second = replayed.drive();

        assertThat(second.status()).isEqualTo(RunStatus.SUCCEEDED);
        String a = recorded.engine().state(recorded.executionRunId().orElseThrow()).node("integrate").outputDigest();
        String b = replayed.engine().state(replayed.executionRunId().orElseThrow()).node("integrate").outputDigest();
        assertThat(Files.readString(tmp.resolve("rep").resolve(replayed.planningRunId().orElseThrow())
                .resolve("worktrees/docs/src/main/java/com/example/codec/core/Base62Codec.java")))
                .isEqualTo(Files.readString(tmp.resolve("rec").resolve(recorded.planningRunId().orElseThrow())
                        .resolve("worktrees/docs/src/main/java/com/example/codec/core/Base62Codec.java")));
        assertThat(b).isNotNull();
        assertThat(a).isNotNull();
        assertThat(JsonlCassetteFile.read(cassette).size()).isEqualTo(scripted.requests.size());

        String out = System.getProperty("smoke.cassette.out");
        if (out != null && !out.isBlank()) {
            Files.copy(cassette, Path.of(out), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    void aChangedRequirementIsACassetteMissNotASilentLiveCall() throws Exception {
        Path cassette = tmp.resolve("empty.jsonl");
        Files.writeString(cassette, "");
        Scenario loaded = ScenarioLoader.load(Path.of("../scenarios/pipeline-smoke"));
        Scenario changed = new Scenario(loaded.name(), "", "A different requirement", loaded.template(), cassette,
                loaded.budget(), loaded.faults());
        Orchestrator o = orchestrator(changed, tmp.resolve("miss"), ModelGateways.create(GatewayMode.REPLAY, cassette,
                () -> { throw new AssertionError("no live gateway in replay"); }));
        RunOutcome outcome = o.drive();
        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(o.engine().state(outcome.runId()).node("requirements").lastFailure())
                .contains(CassetteMissException.class.getSimpleName()).contains("re-record this scenario");
    }
}
