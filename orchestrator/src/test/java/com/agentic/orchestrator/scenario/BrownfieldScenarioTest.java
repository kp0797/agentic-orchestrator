package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.artifacts.InMemoryArtifactStore;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.engine.ApprovalHandler;
import com.agentic.orchestrator.engine.EngineOptions;
import com.agentic.orchestrator.engine.FaultSpec;
import com.agentic.orchestrator.engine.PlanStore;
import com.agentic.orchestrator.engine.RunOutcome;
import com.agentic.orchestrator.engine.Sleeper;
import com.agentic.orchestrator.events.AuditVerifier;
import com.agentic.orchestrator.events.Event;
import com.agentic.orchestrator.events.EventType;
import com.agentic.orchestrator.events.InMemoryEventStore;
import com.agentic.orchestrator.gates.MavenGate;
import com.agentic.orchestrator.gates.RegressionProof;
import com.agentic.orchestrator.gates.WorkspaceGates;
import com.agentic.orchestrator.model.Cassette;
import com.agentic.orchestrator.model.GatewayMode;
import com.agentic.orchestrator.model.JsonlCassetteFile;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The brownfield scenario end to end, with real Maven builds of the whole shortener: record with the
 * scripted agents (once clean, once with an injected gate failure), then replay the clean run from the
 * cassette with no model. Slow (several full builds), so it runs with -Dscenarios=true. With
 * -Dbrownfield.cassette.out=... it also writes the scenario's cassette.
 */
@Tag("scenario")
class BrownfieldScenarioTest {
    @TempDir
    Path tmp;

    private final InMemoryEventStore events = new InMemoryEventStore();

    private Orchestrator orchestrator(Scenario scenario, Path runs, ModelGateway gateway) {
        Orchestrator.Settings settings = new Orchestrator.Settings(runs, PolicyLoader.loadDirectory(Path.of("../policies")),
                ModelSettings.defaults(), new MavenGate.Settings("mvn", true, System.getProperty("maven.repo.local"),
                Duration.ofMinutes(10)), EngineOptions.DEFAULT, Sleeper.NONE);
        return new Orchestrator(scenario, settings, gateway, events, new InMemoryArtifactStore(),
                new PlanStore.InMemory(), ApprovalHandler.autoApprove("brownfield-scenario-test"));
    }

    @Test
    void fixesTheAliasRaceThroughGatesAndApprovalsThenReplaysWithoutAModel() throws Exception {
        Path cassette = tmp.resolve("cassette.jsonl");
        Scenario scenario = ScenarioLoader.load(Path.of("../scenarios/brownfield")).withCassette(cassette);
        assertThat(scenario.regressionProof()).isTrue();

        // 1. Record the clean run.
        Orchestrator recorded = orchestrator(scenario, tmp.resolve("rec"),
                new RecordingModelGateway(new ScriptedModel(BrownfieldScript.answers()), new Cassette(), cassette));
        RunOutcome outcome = recorded.drive();
        String planning = recorded.planningRunId().orElseThrow();
        String execution = recorded.executionRunId().orElseThrow();
        assertThat(outcome.status()).as("%s", outcome).isEqualTo(RunStatus.SUCCEEDED);

        // The codebase analysis ran before planning and pointed at the alias path.
        String impact = recorded.artifacts().get(recorded.engine().state(planning).node("analyze-codebase")
                .outputHashes().get(0)).orElseThrow().contentAsString();
        assertThat(impact).contains("LinkService", "The next migration is V3", "never edit them");

        // The agent's first migration edited the applied V1: the gate refused it, the retry added V3.
        assertThat(log(execution)).anyMatch(e -> e.type() == EventType.GATE_EVALUATED
                && e.nodeId().equals("migrate-unique-codes") && WorkspaceGates.MigrationsImmutable.ID.equals(e.get("gate"))
                && "false".equals(e.get("passed")));
        assertThat(log(execution)).anyMatch(e -> e.type() == EventType.APPROVAL_GRANTED
                && e.nodeId().equals("migrate-unique-codes"));

        // The merged code passed the whole existing suite, and the new tests fail on the original code.
        assertThat(log(execution)).anyMatch(e -> passed(e, "integrate", MavenGate.TESTS));
        assertThat(log(execution)).anyMatch(e -> passed(e, "integrate", RegressionProof.ID));

        Path merged = tmp.resolve("rec").resolve(planning).resolve("worktrees/docs");
        assertThat(merged.resolve("src/main/resources/db/migration/V3__unique_link_codes.sql")).exists();
        assertThat(Files.readString(merged.resolve("src/main/resources/db/migration/V1__create_links.sql")))
                .isEqualTo(Files.readString(Path.of("../scenarios/templates/shortener-v1/src/main/resources/db/migration/"
                        + "V1__create_links.sql")));
        // The fixed service is the human-approved one in shortener/.
        assertThat(Files.readString(merged.resolve(BrownfieldScript.LINK_SERVICE)))
                .isEqualTo(Files.readString(Path.of("../shortener").resolve(BrownfieldScript.LINK_SERVICE)));
        assertThat(AuditVerifier.verify(log(planning)).valid()).isTrue();
        assertThat(AuditVerifier.verify(log(execution)).valid()).isTrue();

        // 2. Record a run with an injected build failure on the fix: retried with feedback, then succeeds.
        Scenario faulty = scenario.withFaults(List.of(FaultSpec.parse("fix-alias-race:gate:build@1")));
        Orchestrator withFault = orchestrator(faulty, tmp.resolve("fault"),
                new RecordingModelGateway(new ScriptedModel(BrownfieldScript.answers()), new Cassette(), cassette));
        RunOutcome faultOutcome = withFault.drive();
        assertThat(faultOutcome.status()).isEqualTo(RunStatus.SUCCEEDED);
        String faultRun = withFault.executionRunId().orElseThrow();
        assertThat(log(faultRun)).anyMatch(e -> e.type() == EventType.FAULT_INJECTED && e.nodeId().equals("fix-alias-race"));
        assertThat(log(faultRun)).filteredOn(e -> e.type() == EventType.NODE_ATTEMPT_STARTED
                && e.nodeId().equals("fix-alias-race")).hasSize(2);

        Cassette combined = dedupe(JsonlCassetteFile.read(cassette));
        Path deduped = tmp.resolve("brownfield.jsonl");
        JsonlCassetteFile.write(combined, deduped);

        // 3. Replay the clean run from the cassette alone.
        ModelGateway replay = ModelGateways.create(GatewayMode.REPLAY, deduped, () -> {
            throw new AssertionError("replay must never build a live gateway");
        });
        Orchestrator replayed = orchestrator(scenario.withCassette(deduped), tmp.resolve("rep"), replay);
        assertThat(replayed.drive().status()).isEqualTo(RunStatus.SUCCEEDED);

        String out = System.getProperty("brownfield.cassette.out");
        if (out != null && !out.isBlank()) {
            Files.copy(deduped, Path.of(out), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<Event> log(String runId) {
        return events.events(runId);
    }

    private static boolean passed(Event e, String node, String gate) {
        return e.type() == EventType.GATE_EVALUATED && e.nodeId().equals(node) && gate.equals(e.get("gate"))
                && "true".equals(e.get("passed"));
    }

    /** Both recordings share most requests; scripted answers are deterministic, so one entry per key is enough. */
    static Cassette dedupe(Cassette all) {
        Map<String, Cassette.Entry> byKey = new LinkedHashMap<>();
        all.all().forEach(e -> byKey.putIfAbsent(e.key(), e));
        Cassette out = new Cassette();
        byKey.values().forEach(out::add);
        return out;
    }
}
