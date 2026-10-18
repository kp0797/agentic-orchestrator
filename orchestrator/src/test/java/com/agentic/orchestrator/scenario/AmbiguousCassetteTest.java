package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.artifacts.InMemoryArtifactStore;
import com.agentic.orchestrator.domain.NodeStatus;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.engine.ApprovalHandler;
import com.agentic.orchestrator.engine.EngineOptions;
import com.agentic.orchestrator.engine.PlanStore;
import com.agentic.orchestrator.engine.RunOutcome;
import com.agentic.orchestrator.engine.Sleeper;
import com.agentic.orchestrator.events.EventType;
import com.agentic.orchestrator.events.InMemoryEventStore;
import com.agentic.orchestrator.gates.MavenGate;
import com.agentic.orchestrator.model.GatewayMode;
import com.agentic.orchestrator.model.ModelGateways;
import com.agentic.orchestrator.model.ModelSettings;
import com.agentic.orchestrator.policy.PolicyLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast check of the committed ambiguous-scenario cassette and of clarifications, in the default build.
 * No Maven builds: everything here happens before code is written.
 */
class AmbiguousCassetteTest {
    @TempDir
    Path runs;

    private final InMemoryEventStore events = new InMemoryEventStore();

    private Orchestrator orchestrator(Scenario scenario) {
        return new Orchestrator(scenario, new Orchestrator.Settings(runs,
                PolicyLoader.loadDirectory(Path.of("../policies")), ModelSettings.defaults(),
                new MavenGate.Settings("mvn", true, System.getProperty("maven.repo.local"), Duration.ofMinutes(5)),
                EngineOptions.DEFAULT, Sleeper.NONE),
                ModelGateways.create(GatewayMode.REPLAY, scenario.cassette(), () -> {
                    throw new AssertionError("replay must never build a live gateway");
                }),
                events, new InMemoryArtifactStore(), new PlanStore.InMemory(), ApprovalHandler.MANUAL);
    }

    @Test
    void theScriptedAnswerResolvesTheOpenQuestionBeforeAnyPlanIsMade() {
        Orchestrator o = orchestrator(ScenarioLoader.load(Path.of("../scenarios/ambiguous")));

        RunOutcome paused = o.drive();

        // The answer was applied automatically; the run now waits for a person to approve the plan.
        assertThat(paused.status()).as("%s", paused).isEqualTo(RunStatus.PAUSED_FOR_APPROVAL);
        assertThat(paused.node("plan")).isEqualTo(NodeStatus.AWAITING_APPROVAL);
        assertThat(events.events(paused.runId())).anyMatch(e -> e.type() == EventType.CLARIFICATION_RECORDED);
        assertThat(o.contexts().get(paused.runId()).spec().orElseThrow().criterionIds()).containsExactly("AC1", "AC2");
    }

    @Test
    void withoutTheScriptAPersonIsAskedAndCanAnswerInTheirOwnWords() {
        Scenario scenario = ScenarioLoader.load(Path.of("../scenarios/ambiguous")).withoutScriptedStakeholder();
        Orchestrator o = orchestrator(scenario);

        RunOutcome asked = o.drive();
        assertThat(asked.status()).isEqualTo(RunStatus.PAUSED_FOR_APPROVAL);
        assertThat(asked.node("requirements")).isEqualTo(NodeStatus.AWAITING_APPROVAL);

        // A person types the product owner's answer: the run continues, and nothing is labelled scripted.
        String answer = ScenarioLoader.load(Path.of("../scenarios/ambiguous")).stakeholder().get(0).message();
        o.clarify("product-owner", answer);
        RunOutcome planned = o.drive();

        assertThat(planned.node("plan")).isEqualTo(NodeStatus.AWAITING_APPROVAL);
        assertThat(events.events(planned.runId())).filteredOn(e -> e.type() == EventType.CLARIFICATION_RECORDED)
                .singleElement().satisfies(e -> assertThat(e.get("scripted")).isEqualTo("false"));
    }
}
