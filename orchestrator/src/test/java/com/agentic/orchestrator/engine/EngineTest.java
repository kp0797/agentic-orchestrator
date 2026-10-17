package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.artifacts.Artifact;
import com.agentic.orchestrator.artifacts.ArtifactDraft;
import com.agentic.orchestrator.artifacts.ArtifactStore;
import com.agentic.orchestrator.artifacts.InMemoryArtifactStore;
import com.agentic.orchestrator.domain.NodeStatus;
import com.agentic.orchestrator.domain.NodeType;
import com.agentic.orchestrator.domain.Plan;
import com.agentic.orchestrator.domain.PlanValidationException;
import com.agentic.orchestrator.domain.RetryPolicy;
import com.agentic.orchestrator.domain.RiskLevel;
import com.agentic.orchestrator.domain.RunBudget;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.domain.TaskNode;
import com.agentic.orchestrator.events.AuditVerifier;
import com.agentic.orchestrator.events.Event;
import com.agentic.orchestrator.events.EventType;
import com.agentic.orchestrator.events.InMemoryEventStore;
import com.agentic.orchestrator.gates.OutputsPresentGate;
import com.agentic.orchestrator.policy.RiskBasedPolicyEngine;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineTest {
    private final InMemoryEventStore events = new InMemoryEventStore();
    private final ArtifactStore artifacts = new InMemoryArtifactStore();
    private final PlanStore plans = new PlanStore.InMemory();

    private static final RetryPolicy FAST = new RetryPolicy(3, Duration.ZERO, 1.0, Duration.ZERO);

    private Engine.Builder builder(NodeExecutor... executors) {
        ExecutorRegistry reg = new ExecutorRegistry();
        for (NodeExecutor e : executors) reg.register(e);
        return Engine.builder().events(events).artifacts(artifacts).plans(plans).executors(reg)
                .policy(new RiskBasedPolicyEngine()).sleeper(Sleeper.NONE);
    }

    private static TaskNode.Builder node(String id, String... deps) {
        return TaskNode.builder(id, NodeType.TEST).executor("x").dependsOn(deps)
                .exitGates(OutputsPresentGate.ID).retryPolicy(FAST);
    }

    private static Plan plan(TaskNode.Builder... nodes) {
        return new Plan("p", 1, java.util.Arrays.stream(nodes).map(TaskNode.Builder::build).toList());
    }

    private List<EventType> types(String runId, String nodeId) {
        return events.events(runId).stream().filter(e -> e.nodeId().equals(nodeId)).map(Event::type).toList();
    }

    private List<String> transitions(String runId, String nodeId) {
        return events.events(runId).stream()
                .filter(e -> e.nodeId().equals(nodeId) && e.type() == EventType.NODE_STATUS_CHANGED)
                .map(e -> e.get("to")).toList();
    }

    @Test
    void runsDiamondToSuccessAndPassesInputsAlongLineage() {
        ScriptedExecutor x = ScriptedExecutor.echo("x");
        Engine engine = builder(x).build();
        RunOutcome o = engine.execute(plan(node("a"), node("b", "a"), node("c", "a"), node("d", "b", "c")),
                RunBudget.UNLIMITED);

        assertThat(o.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(o.nodes().values()).containsOnly(NodeStatus.SUCCEEDED);
        NodeContext d = x.calls.stream().filter(c -> c.node().id().equals("d")).findFirst().orElseThrow();
        assertThat(d.inputs().keySet()).containsExactlyInAnyOrder("b", "c");
        assertThat(d.inputsFrom("b").get(0).contentAsString()).isEqualTo("output of b");
        Artifact out = artifacts.get(engine.state(o.runId()).node("d").outputHashes().get(0)).orElseThrow();
        assertThat(out.lineage().producerNodeId()).isEqualTo("d");
        assertThat(out.lineage().inputHashes()).hasSize(2);
        assertThat(AuditVerifier.verify(events.events(o.runId())).valid()).isTrue();
    }

    @Test
    void independentBranchesRunInParallel() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        ScriptedExecutor x = new ScriptedExecutor("x", ctx -> {
            if (!ctx.node().id().equals("root")) {
                bothStarted.countDown();
                // Each branch waits for the other: this only finishes if they truly overlap.
                if (!bothStarted.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("branches did not run concurrently");
                }
            }
            return NodeResult.of(ArtifactDraft.text("o", ctx.node().id()));
        });
        RunOutcome o = builder(x).build().execute(plan(node("root"), node("left", "root"), node("right", "root")),
                RunBudget.UNLIMITED);
        assertThat(o.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(x.maxConcurrent.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void respectsMaxParallel() {
        ScriptedExecutor x = new ScriptedExecutor("x", ctx -> {
            Thread.sleep(20);
            return NodeResult.of(ArtifactDraft.text("o", ctx.node().id()));
        });
        RunOutcome o = builder(x).options(EngineOptions.DEFAULT.withMaxParallel(2)).build()
                .execute(plan(node("a"), node("b"), node("c"), node("d"), node("e")), RunBudget.UNLIMITED);
        assertThat(o.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(x.maxConcurrent.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void retriesWithFailureFedBackThenSucceeds() {
        ScriptedExecutor x = new ScriptedExecutor("x", ctx -> {
            if (ctx.attempt() == 1) throw new IllegalStateException("compile error: missing symbol LinkRepo");
            return NodeResult.of(ArtifactDraft.text("o", "fixed"));
        });
        RunOutcome o = builder(x).build().execute(plan(node("a")), RunBudget.UNLIMITED);

        assertThat(o.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(x.calls).hasSize(2);
        assertThat(x.calls.get(0).feedback()).isEmpty();
        assertThat(x.calls.get(1).feedback()).get().asString().contains("missing symbol LinkRepo");
        assertThat(transitions(o.runId(), "a")).containsExactly("READY", "RUNNING", "RETRYING", "RUNNING",
                "VERIFYING", "SUCCEEDED");
    }

    @Test
    void gateFailureIsRetriedAndFallsBackToNextExecutor() {
        ScriptedExecutor weak = new ScriptedExecutor("x", ctx -> NodeResult.of(ArtifactDraft.text("o", "")));
        ScriptedExecutor strong = ScriptedExecutor.echo("strong");
        RunOutcome o = builder(weak, strong).build()
                .execute(plan(node("a").fallbacks("strong")), RunBudget.UNLIMITED);

        assertThat(o.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(weak.calls).hasSize(3);
        assertThat(strong.calls).hasSize(1);
        assertThat(strong.calls.get(0).attempt()).isEqualTo(4);
        assertThat(strong.calls.get(0).feedback()).get().asString().contains("outputs-present");
        assertThat(types(o.runId(), "a")).contains(EventType.FALLBACK_ACTIVATED);
    }

    @Test
    void exhaustedNodeFailsCompensatesAndSkipsDependents() {
        ScriptedExecutor x = new ScriptedExecutor("x", ctx -> {
            if (ctx.node().id().equals("b")) throw new RuntimeException("boom");
            return NodeResult.of(ArtifactDraft.text("o", ctx.node().id()));
        });
        RunOutcome o = builder(x).build().execute(plan(node("a"), node("b", "a"), node("c", "b"), node("d", "a")),
                RunBudget.UNLIMITED);

        assertThat(o.status()).isEqualTo(RunStatus.FAILED);
        assertThat(o.node("b")).isEqualTo(NodeStatus.FAILED);
        assertThat(o.node("c")).isEqualTo(NodeStatus.SKIPPED);
        assertThat(o.node("d")).isEqualTo(NodeStatus.SUCCEEDED);
        assertThat(x.callsFor("b")).isEqualTo(3);
        assertThat(x.compensated).containsExactly("b");
    }

    @Test
    void rollbackOnFailureCompensatesSucceededNodesNewestFirst() {
        ScriptedExecutor x = new ScriptedExecutor("x", ctx -> {
            if (ctx.node().id().equals("c")) throw new RuntimeException("boom");
            return NodeResult.of(ArtifactDraft.text("o", ctx.node().id()));
        });
        RunOutcome o = builder(x).options(EngineOptions.DEFAULT.withRollbackOnFailure(true)).build()
                .execute(plan(node("a"), node("b", "a"), node("c", "b")), RunBudget.UNLIMITED);

        assertThat(o.status()).isEqualTo(RunStatus.ROLLED_BACK);
        assertThat(x.compensated).containsExactly("c", "b", "a");
        assertThat(o.node("a")).isEqualTo(NodeStatus.ROLLED_BACK);
        assertThat(o.node("c")).isEqualTo(NodeStatus.FAILED);
    }

    @Test
    void approvalPausesRunAndIsBoundToOutputDigest() {
        ScriptedExecutor x = ScriptedExecutor.echo("x");
        Engine engine = builder(x).build();
        RunOutcome paused = engine.execute(plan(node("a").requiresApproval(true), node("b", "a")),
                RunBudget.UNLIMITED);

        assertThat(paused.status()).isEqualTo(RunStatus.PAUSED_FOR_APPROVAL);
        assertThat(paused.node("a")).isEqualTo(NodeStatus.AWAITING_APPROVAL);
        assertThat(paused.node("b")).isEqualTo(NodeStatus.PENDING);
        String runId = paused.runId();
        String digest = engine.state(runId).node("a").outputDigest();

        assertThatThrownBy(() -> engine.decide(runId, "a", ApprovalDecision.approve("alice", "deadbeef", "")))
                .isInstanceOf(ApprovalMismatchException.class);

        engine.decide(runId, "a", ApprovalDecision.approve("alice", digest, "looks right"));
        RunOutcome done = engine.run(runId);
        assertThat(done.status()).isEqualTo(RunStatus.SUCCEEDED);
        Event granted = events.events(runId).stream().filter(e -> e.type() == EventType.APPROVAL_GRANTED)
                .findFirst().orElseThrow();
        assertThat(granted.get("approver")).isEqualTo("alice");
        assertThat(granted.get("digest")).isEqualTo(digest);
    }

    @Test
    void highRiskNodesNeedApprovalEvenIfPlannerDidNotAsk() {
        Engine engine = builder(ScriptedExecutor.echo("x")).build();
        RunOutcome o = engine.execute(plan(node("migrate").risk(RiskLevel.HIGH)), RunBudget.UNLIMITED);
        assertThat(o.node("migrate")).isEqualTo(NodeStatus.AWAITING_APPROVAL);

        TaskNode migration = TaskNode.builder("m2", NodeType.SCHEMA_MIGRATION).executor("x").risk(RiskLevel.LOW)
                .retryPolicy(FAST).build();
        RunOutcome o2 = engine.execute(new Plan("p2", 1, List.of(migration)), RunBudget.UNLIMITED);
        assertThat(o2.node("m2")).isEqualTo(NodeStatus.AWAITING_APPROVAL);
        Event policy = events.events(o2.runId()).stream().filter(e -> e.type() == EventType.POLICY_EVALUATED)
                .findFirst().orElseThrow();
        assertThat(policy.get("risk")).isEqualTo("HIGH"); // planner said LOW; policy raised it
    }

    @Test
    void rejectionRetriesWithReviewerFeedback() {
        ScriptedExecutor x = new ScriptedExecutor("x", ctx -> NodeResult.of(
                ArtifactDraft.text("o", "v" + ctx.attempt())));
        Engine engine = builder(x).build();
        String runId = engine.execute(plan(node("a").requiresApproval(true)), RunBudget.UNLIMITED).runId();
        String digest = engine.state(runId).node("a").outputDigest();
        engine.decide(runId, "a", ApprovalDecision.reject("bob", digest, "use 302 not 301"));

        RunOutcome o = engine.run(runId);
        assertThat(o.status()).isEqualTo(RunStatus.PAUSED_FOR_APPROVAL);
        assertThat(x.calls.get(1).feedback()).get().asString().contains("use 302 not 301");
        assertThat(engine.state(runId).node("a").outputDigest()).isNotEqualTo(digest);
    }

    @Test
    void autoApproverIsLabelledInTheAuditLog() {
        Engine engine = builder(ScriptedExecutor.echo("x")).approvals(ApprovalHandler.autoApprove("ci")).build();
        RunOutcome o = engine.execute(plan(node("a").requiresApproval(true)), RunBudget.UNLIMITED);
        assertThat(o.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(events.events(o.runId())).anyMatch(e -> e.type() == EventType.APPROVAL_GRANTED
                && e.get("approver").equals("auto-approver:ci"));
    }

    @Test
    void markStaleReRunsDownstreamReusesUpstreamAndInvalidatesApprovals() {
        AtomicInteger designVersion = new AtomicInteger(1);
        ScriptedExecutor x = new ScriptedExecutor("x", ctx -> NodeResult.of(ArtifactDraft.text("o",
                ctx.node().id().equals("design") ? "design v" + designVersion.get() : "built from "
                        + ctx.inputs().values().stream().flatMap(List::stream).map(Artifact::hash).toList())));
        Engine engine = builder(x).approvals(ApprovalHandler.autoApprove("test")).build();
        String runId = engine.execute(plan(node("req"), node("design", "req").requiresApproval(true),
                node("impl", "design")), RunBudget.UNLIMITED).runId();
        String oldImpl = engine.state(runId).node("impl").outputDigest();

        designVersion.set(2);
        List<String> affected = engine.markStale(runId, "design", "requirement clarified");
        assertThat(affected).containsExactly("design", "impl");
        RunOutcome o = engine.run(runId);

        assertThat(o.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(x.callsFor("req")).isEqualTo(1);
        assertThat(x.callsFor("design")).isEqualTo(2);
        assertThat(engine.state(runId).node("impl").outputDigest()).isNotEqualTo(oldImpl);
        assertThat(types(runId, "design")).contains(EventType.APPROVAL_INVALIDATED, EventType.NODE_RESET);
        assertThat(events.events(runId).stream().filter(e -> e.type() == EventType.APPROVAL_GRANTED)).hasSize(2);
    }

    @Test
    void killSwitchCancelsInFlightWorkAndResumeDoesNotRedoFinishedNodes() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        AtomicInteger slowCalls = new AtomicInteger();
        ScriptedExecutor x = new ScriptedExecutor("x", ctx -> {
            if (ctx.node().id().equals("slow") && slowCalls.incrementAndGet() == 1) {
                slowStarted.countDown();
                Thread.sleep(10_000); // interrupted by the safe-stop
            }
            return NodeResult.of(ArtifactDraft.text("o", ctx.node().id()));
        });
        Engine engine = builder(x).build();
        String runId = engine.submit(plan(node("fast"), node("slow", "fast"), node("after", "slow")),
                RunBudget.UNLIMITED);

        Thread stopper = Thread.ofVirtual().start(() -> {
            try {
                slowStarted.await(5, TimeUnit.SECONDS);
                engine.requestStop(runId, "operator pressed the kill switch");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        RunOutcome stopped = engine.run(runId);
        stopper.join();

        assertThat(stopped.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(stopped.reason()).contains("kill switch");
        assertThat(stopped.node("fast")).isEqualTo(NodeStatus.SUCCEEDED);
        assertThat(stopped.node("slow")).isEqualTo(NodeStatus.CANCELLED);

        RunOutcome resumed = engine.run(runId);
        assertThat(resumed.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(x.callsFor("fast")).isEqualTo(1);
        assertThat(AuditVerifier.verify(events.events(runId)).valid()).isTrue();
    }

    @Test
    void tokenBudgetTriggersSafeStop() {
        ScriptedExecutor x = ScriptedExecutor.echo("x"); // 100 tokens per call
        Engine engine = builder(x).build();
        RunOutcome o = engine.execute(plan(node("a"), node("b", "a"), node("c", "b")),
                new RunBudget(Duration.ofMinutes(1), 150, 100));
        assertThat(o.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(o.reason()).contains("budget exceeded: tokens");
        assertThat(o.node("c")).isEqualTo(NodeStatus.PENDING);
        assertThat(events.events(o.runId())).anyMatch(e -> e.type() == EventType.BUDGET_EXCEEDED);
    }

    @Test
    void resumesAfterCrashFromTheEventLogAlone() {
        Plan p = plan(node("a"), node("b", "a"));
        plans.save("crashed-run", p, RunBudget.UNLIMITED);
        Instant t = Instant.now();
        // What a coordinator that died mid-attempt leaves behind: 'a' RUNNING, no result.
        events.append("crashed-run", EventType.RUN_STARTED, null, Map.of(), t);
        events.append("crashed-run", EventType.PLAN_ACCEPTED, null, Map.of("nodes", "2"), t);
        events.append("crashed-run", EventType.NODE_STATUS_CHANGED, "a", Map.of("from", "PENDING", "to", "READY"), t);
        events.append("crashed-run", EventType.NODE_STATUS_CHANGED, "a", Map.of("from", "READY", "to", "RUNNING"), t);
        events.append("crashed-run", EventType.NODE_ATTEMPT_STARTED, "a", Map.of("attempt", "1", "executor", "x"), t);

        Engine fresh = builder(ScriptedExecutor.echo("x")).build(); // a new process: no memory of the run
        RunOutcome o = fresh.run("crashed-run");

        assertThat(o.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(transitions("crashed-run", "a")).containsSubsequence("RUNNING", "CANCELLED", "PENDING", "READY",
                "RUNNING", "VERIFYING", "SUCCEEDED");
        assertThat(fresh.state("crashed-run").node("a").attempts()).isEqualTo(2);
    }

    @Test
    void criticalPolicyDenialSafeStopsTheRun() {
        Engine engine = builder(ScriptedExecutor.echo("x")).policy(new RiskBasedPolicyEngine(Set.of("x"))).build();
        RunOutcome o = engine.execute(plan(node("a")), RunBudget.UNLIMITED);
        assertThat(o.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(o.reason()).contains("critical policy violation");
        assertThat(o.node("a")).isEqualTo(NodeStatus.FAILED);
    }

    @Test
    void missingInputArtifactFailsTheEntryGate() {
        ArtifactStore forgetful = new ArtifactStore() {
            private final InMemoryArtifactStore inner = new InMemoryArtifactStore();
            public Artifact put(Artifact a) { return inner.put(a); }
            public Optional<Artifact> get(String h) { return Optional.empty(); }
        };
        Engine engine = builder(ScriptedExecutor.echo("x")).artifacts(forgetful).build();
        RunOutcome o = engine.execute(plan(node("a"), node("b", "a")), RunBudget.UNLIMITED);
        assertThat(o.node("b")).isEqualTo(NodeStatus.FAILED);
        assertThat(events.events(o.runId())).anyMatch(e -> e.type() == EventType.NODE_STATUS_CHANGED
                && e.get("reason").contains("input artifact"));
    }

    @Test
    void rejectsPlansThatReferenceUnknownExecutorsOrGates() {
        Engine engine = builder(ScriptedExecutor.echo("x")).build();
        Plan bad = new Plan("p", 1, List.of(TaskNode.builder("a", NodeType.TEST).executor("ghost")
                .exitGates("no-such-gate").build()));
        assertThatThrownBy(() -> engine.submit(bad, RunBudget.UNLIMITED))
                .isInstanceOfSatisfying(PlanValidationException.class, e -> assertThat(e.errors())
                        .anyMatch(m -> m.contains("unknown executor 'ghost'"))
                        .anyMatch(m -> m.contains("unknown gate 'no-such-gate'")));
    }

    @Test
    void injectedFaultsAreAlwaysVisibleInTheAuditLog() {
        Engine engine = builder(ScriptedExecutor.echo("x"))
                .faults(FaultInjector.of(FaultSpec.exception("a", "simulated", 1))).build();
        RunOutcome o = engine.execute(plan(node("a")), RunBudget.UNLIMITED);
        assertThat(o.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(types(o.runId(), "a")).contains(EventType.FAULT_INJECTED);
    }

    @Test
    void sameInputsGiveSameOutputsAcrossRuns() {
        List<String> digests = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Engine engine = builder(ScriptedExecutor.echo("x")).build();
            RunOutcome o = engine.execute(plan(node("a"), node("b", "a"), node("c", "a"), node("d", "b", "c")),
                    RunBudget.UNLIMITED);
            digests.add(engine.state(o.runId()).node("d").outputDigest());
        }
        assertThat(digests).containsOnly(digests.get(0));
    }

    @Test
    void stateRebuiltFromLogMatchesLiveState() {
        Engine engine = builder(ScriptedExecutor.echo("x")).approvals(ApprovalHandler.autoApprove("t")).build();
        RunOutcome o = engine.execute(plan(node("a"), node("b", "a").requiresApproval(true)), RunBudget.UNLIMITED);
        RunState rebuilt = RunState.rebuild(o.runId(), plans.load(o.runId()).plan(), events.events(o.runId()));
        assertThat(rebuilt.nodeStatuses()).isEqualTo(o.nodes());
        assertThat(rebuilt.tokensUsed()).isEqualTo(200);
        assertThat(rebuilt.node("b").approvedDigest()).isEqualTo(rebuilt.node("b").outputDigest());
    }
}
