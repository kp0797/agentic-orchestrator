package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.artifacts.ArtifactDraft;
import com.agentic.orchestrator.artifacts.InMemoryArtifactStore;
import com.agentic.orchestrator.domain.NodeType;
import com.agentic.orchestrator.domain.Plan;
import com.agentic.orchestrator.domain.RetryPolicy;
import com.agentic.orchestrator.domain.RunBudget;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.domain.TaskNode;
import com.agentic.orchestrator.events.InMemoryEventStore;
import com.agentic.orchestrator.gates.OutputsPresentGate;
import com.agentic.orchestrator.metrics.PrometheusExporter;
import com.agentic.orchestrator.policy.RiskBasedPolicyEngine;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The /metrics output is derived from the audit log and is valid Prometheus text format. */
class PrometheusMetricsTest {
    private static final RetryPolicy FAST = new RetryPolicy(3, Duration.ZERO, 1.0, Duration.ZERO);

    @Test
    void countsWhatTheAuditLogRecordsInPrometheusTextFormat() {
        InMemoryEventStore events = new InMemoryEventStore();
        PlanStore plans = new PlanStore.InMemory();
        AtomicInteger calls = new AtomicInteger();
        ScriptedExecutor x = new ScriptedExecutor("x", ctx -> {
            if (ctx.node().id().equals("build") && calls.incrementAndGet() == 1) {
                throw new IllegalStateException("flaky \"tool\"\nfailure");
            }
            return NodeResult.of(ArtifactDraft.text("o", ctx.node().id()));
        });
        ExecutorRegistry registry = new ExecutorRegistry().register(x);
        Engine engine = Engine.builder().events(events).artifacts(new InMemoryArtifactStore()).plans(plans)
                .executors(registry).policy(new RiskBasedPolicyEngine()).sleeper(Sleeper.NONE)
                .faults(FaultInjector.of(FaultSpec.gateFailure("check", OutputsPresentGate.ID, "injected", 1)))
                .approvals(ApprovalHandler.autoApprove("metrics-test"))
                .build();
        Plan plan = new Plan("p", 1, List.of(
                TaskNode.builder("build", NodeType.IMPLEMENT).executor("x").exitGates(OutputsPresentGate.ID)
                        .retryPolicy(FAST).build(),
                TaskNode.builder("check", NodeType.TEST).executor("x").dependsOn("build")
                        .exitGates(OutputsPresentGate.ID).retryPolicy(FAST).requiresApproval(true).build()));

        assertThat(engine.execute(plan, RunBudget.UNLIMITED).status()).isEqualTo(RunStatus.SUCCEEDED);
        String text = new PrometheusExporter(events, runId -> Optional.of(plans.load(runId).plan())).render();

        assertThat(text).contains(
                "agentic_runs{status=\"SUCCEEDED\"} 1",
                "agentic_node_attempts_total{node_type=\"IMPLEMENT\"} 2",
                "agentic_node_attempt_failures_total{node_type=\"IMPLEMENT\"} 1",
                "agentic_node_attempts_total{node_type=\"TEST\"} 2",
                "agentic_gate_evaluations_total{gate=\"outputs-present\",result=\"fail\"} 1",
                "agentic_approvals_total{decision=\"granted\"} 1",
                "agentic_faults_injected_total 1",
                "agentic_retries_total 2",
                "agentic_recovered_nodes_total 2",
                "agentic_node_recovery_seconds_count 2",
                "agentic_audit_chains_broken 0",
                "# TYPE agentic_retries_total counter");
        // Every line is a comment or a sample: name{labels} value.
        for (String line : text.split("\n")) {
            assertThat(line).as(line).matches("# (HELP|TYPE) [a-z_]+ .+|[a-z_]+(\\{[a-z_]+=\"[^\"]*\"(,[a-z_]+=\"[^\"]*\")*})? -?[0-9.]+");
        }
    }
}
