package com.agentic.orchestrator.eval;

import com.agentic.orchestrator.artifacts.InMemoryArtifactStore;
import com.agentic.orchestrator.engine.ApprovalHandler;
import com.agentic.orchestrator.engine.EngineOptions;
import com.agentic.orchestrator.engine.FaultSpec;
import com.agentic.orchestrator.engine.PlanStore;
import com.agentic.orchestrator.engine.Sleeper;
import com.agentic.orchestrator.events.InMemoryEventStore;
import com.agentic.orchestrator.gates.MavenGate;
import com.agentic.orchestrator.model.GatewayMode;
import com.agentic.orchestrator.model.ModelGateways;
import com.agentic.orchestrator.model.ModelSettings;
import com.agentic.orchestrator.policy.PolicyLoader;
import com.agentic.orchestrator.scenario.Orchestrator;
import com.agentic.orchestrator.scenario.Scenario;
import com.agentic.orchestrator.scenario.ScenarioLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EvalHarnessTest {
    @TempDir
    Path tmp;

    /** pipeline-smoke from its committed cassette: a real replay with real Maven builds, about 10 s a run. */
    private Orchestrator smoke(List<FaultSpec> faults) {
        Scenario scenario = ScenarioLoader.load(Path.of("../scenarios/pipeline-smoke")).withFaults(faults);
        Orchestrator.Settings settings = new Orchestrator.Settings(tmp.resolve("runs"),
                PolicyLoader.loadDirectory(Path.of("../policies")), ModelSettings.defaults(),
                new MavenGate.Settings("mvn", true, System.getProperty("maven.repo.local"), Duration.ofMinutes(5)),
                EngineOptions.DEFAULT, Sleeper.NONE);
        return new Orchestrator(scenario, settings, ModelGateways.create(GatewayMode.REPLAY, scenario.cassette(), () -> {
            throw new AssertionError("no live gateway in an eval test");
        }), new InMemoryEventStore(), new InMemoryArtifactStore(), new PlanStore.InMemory(),
                ApprovalHandler.autoApprove("eval-test"));
    }

    @Test
    void runsEachScenarioNTimesAndWritesTheSummaryAndAnEvidencePackPerRun() throws Exception {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        EvalHarness harness = new EvalHarness(name -> switch (name) {
            case "pipeline-smoke" -> smoke(List.of());
            // A run that cannot even start is reported as an error, not silently dropped.
            default -> throw new IllegalStateException("no such scenario: " + name);
        }, "replay", new PrintStream(console));

        EvalReport report = harness.run(List.of("pipeline-smoke", "missing"), 2, tmp.resolve("eval"));

        EvalReport.Aggregate smoke = report.aggregate("pipeline-smoke");
        assertThat(smoke.runs()).isEqualTo(2);
        assertThat(smoke.successRate()).isEqualTo(1.0);
        assertThat(smoke.p95Wall()).isPositive();
        assertThat(report.aggregate("missing").successRate()).isZero();
        assertThat(report.runs()).filteredOn(r -> r.scenario().equals("missing"))
                .allMatch(r -> r.status().equals("ERROR") && r.reason().contains("no such scenario"));

        String summary = Files.readString(tmp.resolve("eval/eval-summary.md"));
        assertThat(summary).contains("Mode: **replay**", "measure the orchestrator", "| pipeline-smoke | 2 | 100% |",
                "| missing | 2 | 0% |");
        assertThat(Files.readString(tmp.resolve("eval/eval-summary.json"))).contains("\"successRate\" : 1.0");
        assertThat(tmp.resolve("eval/pipeline-smoke-1/README.md")).exists();
        assertThat(tmp.resolve("eval/pipeline-smoke-2/audit")).isDirectory();
        assertThat(console.toString()).contains("eval pipeline-smoke run 2/2 ... SUCCEEDED");
    }

    @Test
    void aggregatesAreComputedAcrossRuns() {
        ScenarioRunResult clean = ScenarioRunResult.of("s", 1, "SUCCEEDED", "", List.of(), 0, null);
        assertThat(clean.retries()).isZero();
        EvalReport report = new EvalReport("live", List.of(
                result(1, "SUCCEEDED", 10, 2, 1, Duration.ofSeconds(30)),
                result(2, "FAILED", 12, 6, 3, Duration.ofSeconds(50)),
                result(3, "SUCCEEDED", 9, 0, 0, Duration.ofSeconds(20))));

        EvalReport.Aggregate a = report.aggregate("s");

        assertThat(a.successRate()).isEqualTo(2 / 3.0);
        assertThat(a.attemptSuccessRate()).isEqualTo((31 - 8) / 31.0);
        assertThat(a.retriesPerRun()).isEqualTo(4 / 3.0);
        assertThat(a.p50Wall()).isEqualTo(Duration.ofSeconds(30));
        assertThat(a.p95Wall()).isEqualTo(Duration.ofSeconds(50));
        assertThat(report.markdown()).contains("Model answers were live");
    }

    private static ScenarioRunResult result(int i, String status, int attempts, int failed, int retries, Duration wall) {
        return new ScenarioRunResult("s", i, status, "", List.of(), 0, attempts, failed, retries, 0, 0, 0, retries, 2,
                0, wall, Optional.empty(), 0, 0, null);
    }
}
