package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.artifacts.InMemoryArtifactStore;
import com.agentic.orchestrator.domain.NodeStatus;
import com.agentic.orchestrator.domain.RunBudget;
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
import com.agentic.orchestrator.model.ModelSettings;
import com.agentic.orchestrator.planning.PlanProposal;
import com.agentic.orchestrator.planning.PlanningJson;
import com.agentic.orchestrator.policy.PolicyLoader;
import com.agentic.orchestrator.workspace.GitCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full two-stage pipeline with a scripted model: real git worktrees, real tool permissions,
 * real Maven builds and tests in scratch workspaces, real policy gates and a verifiable audit log.
 */
class ScenarioEndToEndTest {
    @TempDir
    Path runs;

    private final InMemoryEventStore events = new InMemoryEventStore();

    private Orchestrator orchestrator(ScriptedModel model) {
        Scenario scenario = new Scenario("codec", "tiny e2e", "Encode numeric ids as short Base62 codes and back.",
                Path.of("src/test/resources/e2e-template"), runs.resolve("cassette.jsonl"),
                new RunBudget(Duration.ofMinutes(20), 5_000_000, 100), List.of());
        MavenGate.Settings maven = new MavenGate.Settings("mvn", true, System.getProperty("maven.repo.local"),
                Duration.ofMinutes(5));
        Orchestrator.Settings settings = new Orchestrator.Settings(runs, PolicyLoader.loadDirectory(Path.of("../policies")),
                ModelSettings.defaults(), maven, EngineOptions.DEFAULT, Sleeper.NONE);
        return new Orchestrator(scenario, settings, model, events, new InMemoryArtifactStore(), new PlanStore.InMemory(),
                ApprovalHandler.autoApprove("e2e-test"));
    }

    private static Map<String, Function<Integer, String>> happyAnswers() {
        return Map.of(
                "requirements", n -> ScriptedModel.SPEC,
                "planner", n -> ScriptedModel.PLAN,
                "architect", n -> ScriptedModel.DESIGN,
                "coder", n -> ScriptedModel.impl(true),
                "tester", n -> ScriptedModel.TESTS,
                "docs", n -> ScriptedModel.DOCS);
    }

    private List<Event> log(String runId) {
        return events.events(runId);
    }

    @Test
    void greenfieldRunsFromRequirementToReleaseThroughRealGates() throws Exception {
        ScriptedModel model = new ScriptedModel(happyAnswers());
        Orchestrator o = orchestrator(model);
        RunOutcome outcome = o.drive();

        assertThat(outcome.status()).as("execution run %s", outcome).isEqualTo(RunStatus.SUCCEEDED);
        String planning = o.planningRunId().orElseThrow();
        String execution = o.executionRunId().orElseThrow();
        assertThat(o.engine().state(planning).status()).isEqualTo(RunStatus.SUCCEEDED);

        // Real gates ran: the merged code compiled and its tests passed.
        assertThat(log(execution)).anyMatch(e -> e.type() == EventType.GATE_EVALUATED && e.nodeId().equals("integrate")
                && "tests".equals(e.get("gate")) && "true".equals(e.get("passed")));
        // The plan and the release were approved, and both approvals are bound to digests.
        assertThat(log(planning)).anyMatch(e -> e.type() == EventType.APPROVAL_GRANTED && e.nodeId().equals("plan"));
        assertThat(log(execution)).anyMatch(e -> e.type() == EventType.APPROVAL_GRANTED
                && e.nodeId().equals("release-readiness"));
        // Tests were written without the implementation: the tester's worktree never contained it.
        Path testerTree = runs.resolve(planning).resolve("worktrees/write-tests");
        assertThat(testerTree.resolve("src/main/java/com/example/codec/core/Base62Codec.java")).doesNotExist();
        assertThat(testerTree.resolve("src/main/java/com/example/codec/api/Codec.java")).exists();
        // The integrated branch has design, code, tests and docs.
        Path merged = runs.resolve(planning).resolve("worktrees/docs");
        assertThat(merged.resolve("src/main/java/com/example/codec/core/Base62Codec.java")).exists();
        assertThat(merged.resolve("src/test/java/com/example/codec/CodecTest.java")).exists();
        assertThat(Files.readString(merged.resolve("README.md"))).contains("Base62");
        assertThat(GitCli.run(merged, "log", "--oneline")).contains("implement (attempt 1)", "write-tests (attempt 1)");
        // Both audit chains verify, and the decision the architect made is on record.
        assertThat(AuditVerifier.verify(log(planning)).valid()).isTrue();
        assertThat(AuditVerifier.verify(log(execution)).valid()).isTrue();
        assertThat(log(execution)).anyMatch(e -> e.type() == EventType.DECISION_RECORDED && "Alphabet".equals(e.get("title")));
        // Every model call carried the versioned prompt id, and token usage was recorded.
        assertThat(model.requests).allMatch(r -> r.params().get("prompt").contains("@v1"));
        assertThat(o.engine().state(execution).tokensUsed()).isPositive();
    }

    @Test
    void failingIntegrationTestsSendTheFailureBackToTheCoder() {
        Map<String, Function<Integer, String>> answers = new java.util.HashMap<>(happyAnswers());
        answers.put("coder", n -> ScriptedModel.impl(n > 1)); // first implementation uses the wrong alphabet
        ScriptedModel model = new ScriptedModel(answers);
        Orchestrator o = orchestrator(model);
        RunOutcome outcome = o.drive();

        assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(model.count("coder")).isEqualTo(2);
        assertThat(model.count("tester")).as("tests are the spec; they are not rewritten").isEqualTo(1);
        String repairPrompt = model.requests.stream().filter(r -> ScriptedModel.role(r).equals("coder"))
                .skip(1).findFirst().orElseThrow().prompt();
        assertThat(repairPrompt).contains("change the implementation, not the tests").contains("ac1_zeroEncodesAsZero");
        String execution = o.executionRunId().orElseThrow();
        assertThat(log(execution)).anyMatch(e -> e.type() == EventType.NODE_RESET && e.nodeId().equals("implement"));
    }

    @Test
    void writesOutsideAnAgentsPermissionsAreRefusedAndLogged() {
        Map<String, Function<Integer, String>> answers = new java.util.HashMap<>(happyAnswers());
        answers.put("tester", n -> n == 1 ? ScriptedModel.TESTS_OUTSIDE_PERMISSIONS : ScriptedModel.TESTS);
        Orchestrator o = orchestrator(new ScriptedModel(answers));
        RunOutcome outcome = o.drive();

        assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(log(o.executionRunId().orElseThrow())).anyMatch(e -> e.type() == EventType.NODE_ATTEMPT_FAILED
                && e.nodeId().equals("write-tests") && e.get("reason").contains("refused by tool permissions")
                && e.get("reason").contains("outside this agent's writable paths"));
    }

    @Test
    void invalidPlansAreRepairedThenFallBackToTheTemplate() {
        Map<String, Function<Integer, String>> answers = new java.util.HashMap<>(happyAnswers());
        answers.put("planner", n -> ScriptedModel.INVALID_PLAN);
        ScriptedModel model = new ScriptedModel(answers);
        Orchestrator o = orchestrator(model);
        RunOutcome outcome = o.drive();

        assertThat(outcome.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(model.count("planner")).isEqualTo(3);
        String planning = o.planningRunId().orElseThrow();
        assertThat(log(planning)).anyMatch(e -> e.type() == EventType.FALLBACK_ACTIVATED
                && "template-planner".equals(e.get("to")));
        assertThat(log(planning)).anyMatch(e -> e.type() == EventType.GATE_EVALUATED && "plan-valid".equals(e.get("gate"))
                && e.get("detail").contains("must be written from the criteria, not from the code"));
        PlanProposal executed = o.contexts().get(o.executionRunId().orElseThrow()).plan().orElseThrow();
        assertThat(executed.source()).isEqualTo("template");
    }

    @Test
    void planApprovalPausesBeforeAnyCodeIsWritten() {
        ScriptedModel model = new ScriptedModel(happyAnswers());
        Scenario scenario = new Scenario("codec", "", "Encode ids", Path.of("src/test/resources/e2e-template"),
                runs.resolve("c.jsonl"), RunBudget.UNLIMITED, List.of());
        Orchestrator o = new Orchestrator(scenario, new Orchestrator.Settings(runs,
                PolicyLoader.loadDirectory(Path.of("../policies")), ModelSettings.defaults(),
                new MavenGate.Settings("mvn", true, System.getProperty("maven.repo.local"), Duration.ofMinutes(5)),
                EngineOptions.DEFAULT, Sleeper.NONE), model, events, new InMemoryArtifactStore(),
                new PlanStore.InMemory(), ApprovalHandler.MANUAL);
        RunOutcome paused = o.drive();

        assertThat(paused.status()).isEqualTo(RunStatus.PAUSED_FOR_APPROVAL);
        assertThat(paused.node("plan")).isEqualTo(NodeStatus.AWAITING_APPROVAL);
        assertThat(o.executionRunId()).isEmpty();
        assertThat(model.count("coder")).isZero();
        PlanProposal proposed = PlanningJson.parse(o.artifacts().get(
                o.engine().state(paused.runId()).node("plan").outputHashes().get(0)).orElseThrow().contentAsString(),
                PlanProposal.class);
        assertThat(proposed.nodes()).hasSize(7);
    }
}
