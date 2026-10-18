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
import com.agentic.orchestrator.model.GatewayMode;
import com.agentic.orchestrator.model.ModelGateways;
import com.agentic.orchestrator.model.ModelSettings;
import com.agentic.orchestrator.policy.PolicyLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays each committed cassette end to end, exactly as {@code docker compose run --rm <scenario>} does.
 * The default build's cassette tests stop at plan approval; this one also covers the execution stage
 * (design, code, tests, docs), so a change to the edit-agent prompt or to the starting code that the
 * execution agents see is caught here. Slow (several real Maven builds), so it runs with -Dscenarios=true.
 */
@Tag("scenario")
class CommittedCassettesReplayTest {
    @TempDir
    Path tmp;

    @ParameterizedTest
    @ValueSource(strings = {"pipeline-smoke", "brownfield", "ambiguous"})
    void committedCassetteReplaysToASuccessfulRelease(String name) {
        Scenario scenario = ScenarioLoader.load(Path.of("../scenarios").resolve(name));
        Orchestrator.Settings settings = new Orchestrator.Settings(tmp.resolve("runs"),
                PolicyLoader.loadDirectory(Path.of("../policies")), ModelSettings.defaults(),
                new MavenGate.Settings("mvn", true, System.getProperty("maven.repo.local"), Duration.ofMinutes(10)),
                EngineOptions.DEFAULT, Sleeper.NONE);
        Orchestrator o = new Orchestrator(scenario, settings,
                ModelGateways.create(GatewayMode.REPLAY, scenario.cassette(), () -> {
                    throw new AssertionError("replay must never call a live model");
                }),
                new InMemoryEventStore(), new InMemoryArtifactStore(), new PlanStore.InMemory(),
                ApprovalHandler.autoApprove("committed-cassette-test"));

        RunOutcome outcome = o.drive();

        assertThat(outcome.status()).as("%s: %s", name, outcome).isEqualTo(RunStatus.SUCCEEDED);
    }
}
