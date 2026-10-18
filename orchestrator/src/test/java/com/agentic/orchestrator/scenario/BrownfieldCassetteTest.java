package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.artifacts.InMemoryArtifactStore;
import com.agentic.orchestrator.domain.NodeStatus;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.engine.ApprovalHandler;
import com.agentic.orchestrator.engine.EngineOptions;
import com.agentic.orchestrator.engine.PlanStore;
import com.agentic.orchestrator.engine.RunOutcome;
import com.agentic.orchestrator.engine.Sleeper;
import com.agentic.orchestrator.events.InMemoryEventStore;
import com.agentic.orchestrator.gates.MavenGate;
import com.agentic.orchestrator.model.GatewayMode;
import com.agentic.orchestrator.model.ModelGateways;
import com.agentic.orchestrator.model.ModelSettings;
import com.agentic.orchestrator.planning.PlanProposal;
import com.agentic.orchestrator.planning.PlanningJson;
import com.agentic.orchestrator.policy.PolicyLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast check of the committed brownfield cassette, in the default build: the planning stage replays
 * from it (spec, codebase analysis, plan) and stops at the plan approval. A prompt or analysis change
 * that would make the cassette stale fails here, not in a demo. The full run is BrownfieldScenarioTest.
 */
class BrownfieldCassetteTest {
    @TempDir
    Path runs;

    @Test
    void thePlanningStageReplaysFromTheCommittedCassette() {
        Scenario scenario = ScenarioLoader.load(Path.of("../scenarios/brownfield"));
        Orchestrator o = new Orchestrator(scenario, new Orchestrator.Settings(runs,
                PolicyLoader.loadDirectory(Path.of("../policies")), ModelSettings.defaults(),
                new MavenGate.Settings("mvn", true, System.getProperty("maven.repo.local"), Duration.ofMinutes(5)),
                EngineOptions.DEFAULT, Sleeper.NONE),
                ModelGateways.create(GatewayMode.REPLAY, scenario.cassette(), () -> {
                    throw new AssertionError("replay must never build a live gateway");
                }),
                new InMemoryEventStore(), new InMemoryArtifactStore(), new PlanStore.InMemory(), ApprovalHandler.MANUAL);

        RunOutcome paused = o.drive();

        assertThat(paused.status()).as("%s", paused).isEqualTo(RunStatus.PAUSED_FOR_APPROVAL);
        assertThat(paused.node("analyze-codebase")).isEqualTo(NodeStatus.SUCCEEDED);
        assertThat(paused.node("plan")).isEqualTo(NodeStatus.AWAITING_APPROVAL);
        PlanProposal plan = PlanningJson.parse(o.artifacts().get(o.engine().state(paused.runId()).node("plan")
                .outputHashes().get(0)).orElseThrow().contentAsString(), PlanProposal.class);
        assertThat(plan.nodes()).anyMatch(n -> n.type().equals("SCHEMA_MIGRATION") && n.requiresApproval());
        assertThat(o.contexts().get(paused.runId()).codebase()).contains("LinkService");
    }
}
