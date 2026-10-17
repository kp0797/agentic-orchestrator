package com.agentic.orchestrator.demo;

import com.agentic.orchestrator.artifacts.InMemoryArtifactStore;
import com.agentic.orchestrator.domain.Plan;
import com.agentic.orchestrator.domain.RunBudget;
import com.agentic.orchestrator.engine.ApprovalDecision;
import com.agentic.orchestrator.engine.Engine;
import com.agentic.orchestrator.engine.EngineOptions;
import com.agentic.orchestrator.engine.ExecutorRegistry;
import com.agentic.orchestrator.engine.FaultInjector;
import com.agentic.orchestrator.engine.FaultSpec;
import com.agentic.orchestrator.engine.RunOutcome;
import com.agentic.orchestrator.events.AuditVerifier;
import com.agentic.orchestrator.events.EventStore;
import com.agentic.orchestrator.events.InMemoryEventStore;
import com.agentic.orchestrator.gates.NoSecretsGate;
import com.agentic.orchestrator.gates.OutputsPresentGate;
import com.agentic.orchestrator.metrics.RunMetrics;
import com.agentic.orchestrator.policy.RiskBasedPolicyEngine;

import java.io.PrintStream;
import java.time.Duration;

/**
 * Day-1 exit check: an 8-node DAG with fake agents runs in parallel, fails, retries, falls back,
 * pauses for approval, safe-stops on budget, resumes, re-plans after an upstream change, rolls back,
 * and every step is visible in a verifiable audit log. No LLM is involved.
 */
public final class FakeDagDemo {
    private final PrintStream out;

    public FakeDagDemo(PrintStream out) {
        this.out = out;
    }

    public static void main(String[] args) {
        new FakeDagDemo(System.out).runAll();
    }

    public void runAll() {
        scenarioRetriesFallbackApproval();
        scenarioBudgetStopAndResume();
        scenarioRollbackOnFailure();
    }

    private Engine engine(EventStore events, FaultInjector faults, EngineOptions options) {
        ExecutorRegistry executors = new ExecutorRegistry()
                .register(new FakeAgent("fake", Duration.ofMillis(60), 1_000))
                .register(new FakeAgent("fake-strong", Duration.ofMillis(90), 3_000));
        return Engine.builder()
                .events(events)
                .artifacts(new InMemoryArtifactStore())
                .executors(executors)
                .policy(new RiskBasedPolicyEngine())
                .faults(faults)
                .options(options)
                .build();
    }

    void scenarioRetriesFallbackApproval() {
        header("1. Parallel fan-out, retry with feedback, fallback, approvals, re-plan");
        EventStore events = new InMemoryEventStore();
        FaultInjector faults = FaultInjector.of(
                FaultSpec.exception("implement", "simulated compile error in LinkService", 1),
                FaultSpec.gateFailure("integrate", NoSecretsGate.ID, "simulated leaked token in merged diff", 1, 2, 3));
        Engine engine = engine(events, faults, EngineOptions.DEFAULT);
        Plan plan = DemoPlans.greenfield();
        String runId = engine.submit(plan, RunBudget.UNLIMITED);

        RunOutcome o = engine.run(runId);
        report(o);
        approveAndContinue(engine, runId, o);
        RunOutcome done = engine.run(runId);
        if (done.status().name().equals("PAUSED_FOR_APPROVAL")) {
            approveAndContinue(engine, runId, done);
            done = engine.run(runId);
        }
        report(done);

        out.println("\n  Upstream change: the design is revised, so design and everything after it is stale.");
        out.println("  Re-planned nodes: " + engine.markStale(runId, "design", "design revised after review"));
        RunOutcome replanned = engine.run(runId);
        while (replanned.status().name().equals("PAUSED_FOR_APPROVAL")) {
            approveAndContinue(engine, runId, replanned);
            replanned = engine.run(runId);
        }
        report(replanned);
        timelineAndMetrics(events, runId);
    }

    void scenarioBudgetStopAndResume() {
        header("2. Safe-stop on token budget, then resume without redoing finished work");
        EventStore events = new InMemoryEventStore();
        Engine engine = engine(events, FaultInjector.NONE,
                EngineOptions.DEFAULT);
        Plan plan = DemoPlans.greenfield();
        String runId = engine.submit(plan, new RunBudget(Duration.ofMinutes(5), 3_500, 10.0));
        RunOutcome stopped = engine.run(runId);
        while (stopped.status().name().equals("PAUSED_FOR_APPROVAL")) {
            approveAndContinue(engine, runId, stopped);
            stopped = engine.run(runId);
        }
        report(stopped);
        out.println("\n  Raising the token budget and resuming.");
        engine.updateBudget(runId, new RunBudget(Duration.ofMinutes(5), 100_000, 10.0));
        RunOutcome o = engine.run(runId);
        while (o.status().name().equals("PAUSED_FOR_APPROVAL")) {
            approveAndContinue(engine, runId, o);
            o = engine.run(runId);
        }
        report(o);
        timelineAndMetrics(events, runId);
    }

    void scenarioRollbackOnFailure() {
        header("3. Unrecoverable failure: compensate succeeded work, newest first");
        EventStore events = new InMemoryEventStore();
        FaultInjector faults = FaultInjector.of(
                FaultSpec.gateFailure("security-scan", OutputsPresentGate.ID, "scanner found a critical issue", 1, 2, 3));
        Engine engine = engine(events, faults, EngineOptions.DEFAULT.withRollbackOnFailure(true));
        String runId = engine.submit(DemoPlans.greenfield(), RunBudget.UNLIMITED);
        RunOutcome o = engine.run(runId);
        while (o.status().name().equals("PAUSED_FOR_APPROVAL")) {
            approveAndContinue(engine, runId, o);
            o = engine.run(runId);
        }
        report(o);
        timelineAndMetrics(events, runId);
    }

    private void approveAndContinue(Engine engine, String runId, RunOutcome o) {
        o.nodes().forEach((id, status) -> {
            if (status.name().equals("AWAITING_APPROVAL")) {
                String digest = engine.state(runId).node(id).outputDigest();
                out.println("  [human] reviewing '" + id + "' output " + digest.substring(0, 12) + "... approved");
                engine.decide(runId, id, ApprovalDecision.approve("kamakshya (demo reviewer)", digest,
                        "reviewed in demo"));
            }
        });
    }

    private void report(RunOutcome o) {
        out.println("  -> run " + o.runId() + " is " + o.status()
                + (o.reason().isEmpty() ? "" : " (" + o.reason() + ")"));
    }

    private void timelineAndMetrics(EventStore events, String runId) {
        out.println("\n  Audit timeline:");
        EventPrinter.print(events.events(runId), out, false);
        AuditVerifier.Result verify = AuditVerifier.verify(events.events(runId));
        out.println("\n  Audit chain: " + (verify.valid() ? "VALID" : "BROKEN") + ", " + verify.checked()
                + " events, head " + (verify.headHash() == null ? "-" : verify.headHash().substring(0, 16)));
        out.println("  Metrics: " + RunMetrics.from(events.events(runId)).summary());
    }

    private void header(String title) {
        out.println("\n" + "=".repeat(100) + "\n" + title + "\n" + "=".repeat(100));
    }
}
