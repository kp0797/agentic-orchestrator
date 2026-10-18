package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.artifacts.InMemoryArtifactStore;
import com.agentic.orchestrator.domain.NodeStatus;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.engine.ApprovalHandler;
import com.agentic.orchestrator.engine.EngineOptions;
import com.agentic.orchestrator.engine.PlanStore;
import com.agentic.orchestrator.engine.RunOutcome;
import com.agentic.orchestrator.engine.Sleeper;
import com.agentic.orchestrator.events.AuditVerifier;
import com.agentic.orchestrator.events.Event;
import com.agentic.orchestrator.events.EventType;
import com.agentic.orchestrator.events.InMemoryEventStore;
import com.agentic.orchestrator.gates.MavenGate;
import com.agentic.orchestrator.model.Cassette;
import com.agentic.orchestrator.model.GatewayMode;
import com.agentic.orchestrator.model.ModelGateway;
import com.agentic.orchestrator.model.ModelGateways;
import com.agentic.orchestrator.model.ModelSettings;
import com.agentic.orchestrator.model.RecordingModelGateway;
import com.agentic.orchestrator.policy.PolicyLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ambiguous scenario end to end on the real shortener: an open privacy question answered by the
 * (scripted) product owner, then a design review that changes scope mid-run, which supersedes the
 * running plan and re-plans. Records, then replays without a model. Slow: -Dscenarios=true.
 * With -Dambiguous.cassette.out=... it writes the scenario's cassette.
 */
@Tag("scenario")
class AmbiguousScenarioTest {
    @TempDir
    Path tmp;

    private final InMemoryEventStore events = new InMemoryEventStore();

    private Orchestrator orchestrator(Scenario scenario, Path runs, ModelGateway gateway) {
        Orchestrator.Settings settings = new Orchestrator.Settings(runs, PolicyLoader.loadDirectory(Path.of("../policies")),
                ModelSettings.defaults(), new MavenGate.Settings("mvn", true, System.getProperty("maven.repo.local"),
                Duration.ofMinutes(10)), EngineOptions.DEFAULT, Sleeper.NONE);
        return new Orchestrator(scenario, settings, gateway, events, new InMemoryArtifactStore(),
                new PlanStore.InMemory(), ApprovalHandler.autoApprove("ambiguous-scenario-test"));
    }

    @Test
    void asksBeforeGuessingThenRePlansWhenScopeChangesMidRun() throws Exception {
        Path cassette = tmp.resolve("cassette.jsonl");
        Scenario scenario = ScenarioLoader.load(Path.of("../scenarios/ambiguous")).withCassette(cassette);
        assertThat(scenario.stakeholder()).hasSize(2);

        ScriptedModel model = new ScriptedModel(AmbiguousScript.answers());
        Orchestrator o = orchestrator(scenario, tmp.resolve("rec"),
                new RecordingModelGateway(model, new Cassette(), cassette));
        RunOutcome outcome = o.drive();
        assertThat(outcome.status()).as("%s", outcome).isEqualTo(RunStatus.SUCCEEDED);
        String planning = o.planningRunId().orElseThrow();
        String execution = o.executionRunId().orElseThrow();

        // 1. The privacy question stopped the spec; the answer went back to the requirements agent.
        assertThat(log(planning)).anyMatch(e -> e.type() == EventType.GATE_EVALUATED && "spec-valid".equals(e.get("gate"))
                && e.get("detail").contains("low-confidence"));
        assertThat(log(planning)).filteredOn(e -> e.type() == EventType.CLARIFICATION_RECORDED).hasSize(2)
                .allMatch(e -> "true".equals(e.get("scripted")) && "product-owner".equals(e.get("from")));
        assertThat(log(planning)).anyMatch(e -> e.type() == EventType.APPROVAL_REJECTED
                && e.nodeId().equals("requirements") && e.get("feedback").contains("never stored"));
        assertThat(model.count("requirements")).isEqualTo(3);

        // 2. The design review changed scope: the first execution run was superseded and its work undone.
        assertThat(o.supersededRuns()).hasSize(1);
        String superseded = o.supersededRuns().get(0);
        assertThat(o.engine().state(superseded).status()).isEqualTo(RunStatus.STOPPED);
        assertThat(o.engine().state(superseded).node("design").status()).isEqualTo(NodeStatus.CANCELLED);
        assertThat(log(superseded)).anyMatch(e -> e.type() == EventType.COMPENSATION_EXECUTED
                && e.nodeId().equals("design"));
        assertThat(log(planning)).anyMatch(e -> e.type() == EventType.APPROVAL_INVALIDATED && e.nodeId().equals("plan"));

        // 3. Plan v2 has the new test task, and it ran to a release.
        assertThat(o.plans().load(execution).plan().version()).isEqualTo(2);
        assertThat(o.engine().state(execution).node("referrer-visitor-tests").status()).isEqualTo(NodeStatus.SUCCEEDED);
        assertThat(log(execution)).anyMatch(e -> e.type() == EventType.GATE_EVALUATED && e.nodeId().equals("integrate")
                && MavenGate.TESTS.equals(e.get("gate")) && "true".equals(e.get("passed")));
        assertThat(log(execution)).anyMatch(e -> e.type() == EventType.APPROVAL_GRANTED
                && e.nodeId().equals("add-visitor-hash"));
        Path merged = tmp.resolve("rec").resolve(planning).resolve("worktrees/docs");
        assertThat(Files.readString(merged.resolve("docs/decisions/0001-unique-visitors.md"))).contains("topReferrers");
        assertThat(merged.resolve("src/main/java/com/agentic/shortener/clicks/VisitorHasher.java")).exists();
        for (String run : List.of(planning, superseded, execution)) {
            assertThat(AuditVerifier.verify(log(run)).valid()).as(run).isTrue();
        }

        // Replay from the cassette alone reaches the same end.
        ModelGateway replay = ModelGateways.create(GatewayMode.REPLAY, cassette, () -> {
            throw new AssertionError("replay must never build a live gateway");
        });
        Orchestrator replayed = orchestrator(scenario, tmp.resolve("rep"), replay);
        assertThat(replayed.drive().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(replayed.supersededRuns()).hasSize(1);

        String out = System.getProperty("ambiguous.cassette.out");
        if (out != null && !out.isBlank()) {
            Files.copy(cassette, Path.of(out), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<Event> log(String runId) {
        return events.events(runId);
    }
}
