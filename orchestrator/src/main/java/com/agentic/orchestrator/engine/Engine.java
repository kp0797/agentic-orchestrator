package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.artifacts.Artifact;
import com.agentic.orchestrator.artifacts.ArtifactDraft;
import com.agentic.orchestrator.artifacts.ArtifactStore;
import com.agentic.orchestrator.artifacts.Hashing;
import com.agentic.orchestrator.artifacts.Lineage;
import com.agentic.orchestrator.domain.DecisionRecord;
import com.agentic.orchestrator.domain.NodeStatus;
import com.agentic.orchestrator.domain.Plan;
import com.agentic.orchestrator.domain.PlanValidationException;
import com.agentic.orchestrator.domain.RunBudget;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.domain.TaskNode;
import com.agentic.orchestrator.events.EventStore;
import com.agentic.orchestrator.events.EventType;
import com.agentic.orchestrator.gates.Gate;
import com.agentic.orchestrator.gates.GateContext;
import com.agentic.orchestrator.gates.GateRegistry;
import com.agentic.orchestrator.gates.GateResult;
import com.agentic.orchestrator.policy.PolicyDecision;
import com.agentic.orchestrator.policy.PolicyEngine;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The deterministic control plane. One coordinator thread per run owns all state changes; executors
 * run in parallel on virtual threads and only return results. Every decision the engine makes is an
 * event in the hash-chained log, and run state is rebuilt from that log, so a stopped or crashed run
 * resumes exactly where it was.
 *
 * <p>The engine never calls an LLM. Executors may; gates and policy may not.
 */
public final class Engine {
    private final EventStore events;
    private final ArtifactStore artifacts;
    private final ExecutorRegistry executors;
    private final GateRegistry gates;
    private final PolicyEngine policy;
    private final PlanStore plans;
    private final ApprovalHandler approvalHandler;
    private final FaultInjector faults;
    private final Sleeper sleeper;
    private final Clock clock;
    private final EngineOptions options;
    private final Supplier<String> runIdSupplier;
    private final Map<String, RunControl> controls = new ConcurrentHashMap<>();

    private Engine(Builder b) {
        this.events = Objects.requireNonNull(b.events, "events");
        this.artifacts = Objects.requireNonNull(b.artifacts, "artifacts");
        this.executors = Objects.requireNonNull(b.executors, "executors");
        this.gates = Objects.requireNonNull(b.gates, "gates");
        this.policy = Objects.requireNonNull(b.policy, "policy");
        this.plans = b.plans;
        this.approvalHandler = b.approvalHandler;
        this.faults = b.faults;
        this.sleeper = b.sleeper;
        this.clock = b.clock;
        this.options = b.options;
        this.runIdSupplier = b.runIdSupplier;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------------------------

    /** Validates bindings, records the accepted plan and returns a run id. Nothing executes yet. */
    public String submit(Plan plan, RunBudget budget) {
        validateBindings(plan);
        String runId = runIdSupplier.get();
        plans.save(runId, plan, budget);
        Session s = new Session(runId, plan);
        s.emit(EventType.RUN_STARTED, null, Map.of(
                "planId", plan.id(),
                "planVersion", String.valueOf(plan.version()),
                "maxTokens", String.valueOf(budget.maxTokens()),
                "maxCostUsd", String.valueOf(budget.maxCostUsd()),
                "maxWallTime", budget.maxWallTime().toString()));
        s.emit(EventType.PLAN_ACCEPTED, null, Map.of(
                "planDigest", plan.digest(),
                "nodes", String.valueOf(plan.nodes().size()),
                "order", String.join(",", plan.topologicalOrder())));
        return runId;
    }

    /** Submit and drive to completion, a pause for approval, or a safe-stop. */
    public RunOutcome execute(Plan plan, RunBudget budget) {
        return run(submit(plan, budget));
    }

    /**
     * Drives a run. Also the resume entry point: in-flight work from a stop or crash is reset and the
     * run continues from the log; nodes that already succeeded are not run again.
     */
    public RunOutcome run(String runId) {
        RunControl ctl = control(runId);
        Session s;
        synchronized (ctl) {
            if (ctl.active) {
                throw new IllegalStateException("run " + runId + " is already being driven");
            }
            s = load(runId);
            ctl.active = true;
            ctl.stopReason = null;
        }
        try {
            return drive(s, ctl);
        } finally {
            synchronized (ctl) {
                drainCommands(ctl, s);
                ctl.active = false;
            }
        }
    }

    /**
     * Records a human decision. If the run is being driven, the decision is applied by its coordinator;
     * otherwise it is applied now and the caller resumes with {@link #run(String)}.
     *
     * @throws ApprovalMismatchException if the digest is not the node's current output
     */
    public void decide(String runId, String nodeId, ApprovalDecision decision) {
        withSession(runId, s -> applyDecision(s, s.plan.node(nodeId), decision));
    }

    /** Safe-stop: cancels in-flight work, records why, and leaves the run resumable. */
    public void requestStop(String runId, String reason) {
        RunControl ctl = control(runId);
        synchronized (ctl) {
            if (ctl.active) {
                ctl.stopReason = reason;
                return;
            }
        }
        withSession(runId, s -> {
            if (s.state.status() == RunStatus.RUNNING || s.state.status() == RunStatus.PAUSED_FOR_APPROVAL) {
                s.emit(EventType.RUN_STOPPED, null, Map.of("reason", reason));
            }
        });
    }

    /**
     * Marks a node and everything downstream stale, as when an upstream artifact changed. Their
     * approvals are invalidated and they get a fresh retry budget; untouched nodes are reused.
     */
    public List<String> markStale(String runId, String nodeId, String reason) {
        return markStale(runId, nodeId, reason, null);
    }

    /**
     * As {@link #markStale(String, String, String)}, and hands {@code feedback} to the re-run of
     * {@code nodeId} (for example the integration test failures its code caused).
     */
    public List<String> markStale(String runId, String nodeId, String reason, String feedback) {
        List<String> affected = new ArrayList<>();
        withSession(runId, s -> {
            for (String id : s.plan.downstreamInclusive(nodeId)) {
                NodeRuntime rt = s.state.node(id);
                NodeStatus st = rt.status();
                if (st == NodeStatus.PENDING && rt.attempts() == 0) {
                    continue;
                }
                if (st.isInFlight()) {
                    throw new IllegalStateException("node " + id + " is in flight; stop the run first");
                }
                if (st == NodeStatus.SUCCEEDED || st == NodeStatus.AWAITING_APPROVAL || st == NodeStatus.PENDING) {
                    if (st != NodeStatus.PENDING) {
                        s.transition(id, NodeStatus.STALE, reason);
                    }
                }
                if (rt.approvedDigest() != null) {
                    s.emit(EventType.APPROVAL_INVALIDATED, id, Map.of("digest", rt.approvedDigest(),
                            "reason", "upstream change: " + reason));
                }
                if (s.state.node(id).status() != NodeStatus.PENDING) {
                    s.transition(id, NodeStatus.PENDING, "re-plan: " + reason);
                }
                Map<String, String> reset = new LinkedHashMap<>(Map.of("reason", reason));
                if (feedback != null && id.equals(nodeId)) {
                    reset.put("feedback", feedback);
                }
                s.emit(EventType.NODE_RESET, id, reset);
                affected.add(id);
            }
        });
        return affected;
    }

    /** Compensates a node and every succeeded node downstream of it, newest first. */
    public List<String> rollback(String runId, String nodeId, String reason) {
        List<String> undone = new ArrayList<>();
        withSession(runId, s -> {
            List<String> targets = new ArrayList<>(s.plan.downstreamInclusive(nodeId));
            java.util.Collections.reverse(targets);
            for (String id : targets) {
                if (s.state.node(id).status() == NodeStatus.SUCCEEDED) {
                    compensate(s, s.plan.node(id), reason);
                    s.transition(id, NodeStatus.ROLLED_BACK, reason);
                    undone.add(id);
                }
            }
        });
        return undone;
    }

    /**
     * Records a clarification in the run's audit log. It changes no state by itself: the caller decides
     * what it invalidates (see {@link #markStale(String, String, String, String)}).
     *
     * @param scripted true when it came from a scenario script rather than a person
     */
    public void recordClarification(String runId, String from, String text, boolean scripted) {
        withSession(runId, s -> s.emit(EventType.CLARIFICATION_RECORDED, null, Map.of(
                "from", from, "text", text, "scripted", String.valueOf(scripted))));
    }

    /**
     * Ends a run that a newer plan replaces: finished work is compensated and rolled back (latest first),
     * pending approvals are withdrawn, and nothing else will start. The run must not be in flight; stop it
     * with {@link #requestStop} first.
     */
    public void supersede(String runId, String reason) {
        RunControl ctl = control(runId);
        synchronized (ctl) {
            if (ctl.active) {
                throw new IllegalStateException("run " + runId + " is still being driven; stop it first");
            }
        }
        withSession(runId, s -> {
            List<String> order = new ArrayList<>(s.plan.topologicalOrder());
            java.util.Collections.reverse(order);
            for (String id : order) {
                NodeStatus st = s.state.node(id).status();
                if (st == NodeStatus.SUCCEEDED) {
                    compensate(s, s.plan.node(id), reason);
                    s.transition(id, NodeStatus.ROLLED_BACK, reason);
                } else if (st == NodeStatus.AWAITING_APPROVAL) {
                    compensate(s, s.plan.node(id), reason);
                    s.transition(id, NodeStatus.CANCELLED, reason + " (approval withdrawn)");
                } else if (st == NodeStatus.PENDING) {
                    s.transition(id, NodeStatus.CANCELLED, reason);
                }
            }
            if (s.state.status() == RunStatus.RUNNING || s.state.status() == RunStatus.PAUSED_FOR_APPROVAL) {
                s.emit(EventType.RUN_STOPPED, null, Map.of("reason", reason));
            }
        });
    }

    /** Raises or lowers a run's limits, e.g. before resuming a run stopped for budget. */
    public void updateBudget(String runId, RunBudget budget) {
        withSession(runId, s -> {
            plans.save(runId, s.plan, budget);
            s.emit(EventType.BUDGET_UPDATED, null, Map.of(
                    "maxTokens", String.valueOf(budget.maxTokens()),
                    "maxCostUsd", String.valueOf(budget.maxCostUsd()),
                    "maxWallTime", budget.maxWallTime().toString()));
        });
    }

    /** Current state, rebuilt from the event log. */
    public RunState state(String runId) {
        return load(runId).state;
    }

    // ------------------------------------------------------------------------------------------
    // Coordinator loop
    // ------------------------------------------------------------------------------------------

    private RunOutcome drive(Session s, RunControl ctl) {
        RunState st = s.state;
        if (st.status() == RunStatus.SUCCEEDED && st.allNodes(NodeStatus.SUCCEEDED)) {
            return s.outcome();
        }
        prepareResume(s);

        Map<String, Future<AttemptOutcome>> running = new LinkedHashMap<>();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        ExecutorCompletionService<AttemptOutcome> completions = new ExecutorCompletionService<>(pool);
        try {
            while (true) {
                drainCommands(ctl, s);
                String stop = stopReason(ctl, s);
                if (stop != null) {
                    safeStop(s, running, stop);
                    return s.outcome();
                }
                schedule(s, ctl, completions, running);
                if (ctl.stopReason != null) {
                    continue; // a critical policy denial asked for a stop while scheduling
                }
                if (running.isEmpty()) {
                    Optional<RunOutcome> settled = settle(s);
                    if (settled.isPresent()) {
                        return settled.get();
                    }
                    continue;
                }
                Future<AttemptOutcome> done = completions.poll(options.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
                if (done != null) {
                    AttemptOutcome outcome = done.get();
                    if (running.remove(outcome.nodeId()) != null) {
                        handle(s, outcome);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            safeStop(s, running, "coordinator interrupted");
            return s.outcome();
        } catch (ExecutionException e) {
            // Workers catch everything, so this is an engine bug. Stop safely rather than guess.
            safeStop(s, running, "engine error: " + e.getCause());
            return s.outcome();
        } finally {
            pool.shutdownNow();
        }
    }

    private void prepareResume(Session s) {
        RunState st = s.state;
        boolean resumed = false;
        for (String id : s.plan.topologicalOrder()) {
            NodeStatus status = st.node(id).status();
            if (status == NodeStatus.READY) {
                s.transition(id, NodeStatus.PENDING, "resume: work was not started");
                resumed = true;
            } else if (status.isInFlight()) {
                s.transition(id, NodeStatus.CANCELLED, "resume: work was in flight when the run stopped");
                s.transition(id, NodeStatus.PENDING, "resume");
                resumed = true;
            } else if (status == NodeStatus.CANCELLED || status == NodeStatus.SKIPPED) {
                s.transition(id, NodeStatus.PENDING, "resume");
                resumed = true;
            }
        }
        if (st.status() != RunStatus.RUNNING || resumed) {
            s.emit(EventType.RUN_RESUMED, null, Map.of("previousStatus", st.status().name()));
        }
    }

    private void schedule(Session s, RunControl ctl, ExecutorCompletionService<AttemptOutcome> completions,
                          Map<String, Future<AttemptOutcome>> running) {
        RunState st = s.state;
        for (String id : s.plan.topologicalOrder()) {
            if (running.containsKey(id)) {
                continue;
            }
            TaskNode node = s.plan.node(id);
            NodeRuntime rt = st.node(id);
            if (rt.status() == NodeStatus.PENDING) {
                Optional<String> blocker = node.dependsOn().stream()
                        .filter(d -> st.node(d).status().blocksDependents())
                        .findFirst();
                if (blocker.isPresent()) {
                    s.transition(id, NodeStatus.SKIPPED, "dependency '" + blocker.get() + "' is "
                            + st.node(blocker.get()).status());
                    continue;
                }
                boolean depsDone = node.dependsOn().stream()
                        .allMatch(d -> st.node(d).status() == NodeStatus.SUCCEEDED);
                if (!depsDone || running.size() >= options.maxParallel()) {
                    continue;
                }
                if (!passEntryGates(s, ctl, node)) {
                    continue;
                }
                s.transition(id, NodeStatus.READY, "entry gates passed");
                launch(s, node, completions, running);
            } else if (rt.status() == NodeStatus.RETRYING && running.size() < options.maxParallel()) {
                launch(s, node, completions, running);
            }
        }
    }

    private boolean passEntryGates(Session s, RunControl ctl, TaskNode node) {
        PolicyDecision decision = policy.evaluate(node);
        s.emit(EventType.POLICY_EVALUATED, node.id(), Map.of(
                "verdict", decision.verdict().name(),
                "risk", decision.effectiveRisk().name(),
                "approvalRequired", String.valueOf(decision.approvalRequired()),
                "critical", String.valueOf(decision.critical()),
                "reason", decision.reason()));
        if (!decision.allowed()) {
            s.transition(node.id(), NodeStatus.FAILED, "entry gate: policy denied: " + decision.reason());
            if (decision.critical()) {
                ctl.stopReason = "critical policy violation at '" + node.id() + "': " + decision.reason();
            }
            return false;
        }
        for (String dep : node.dependsOn()) {
            for (String hash : s.state.node(dep).outputHashes()) {
                if (!artifacts.contains(hash)) {
                    s.transition(node.id(), NodeStatus.FAILED,
                            "entry gate: input artifact " + hash + " from '" + dep + "' is missing");
                    return false;
                }
            }
        }
        return true;
    }

    private void launch(Session s, TaskNode node, ExecutorCompletionService<AttemptOutcome> completions,
                        Map<String, Future<AttemptOutcome>> running) {
        NodeRuntime rt = s.state.node(node.id());
        int attempt = rt.attempts() + 1;
        List<String> chain = node.executorChain();
        int idx = node.executorIndexForAttempt(attempt);
        String executorKey = chain.get(idx);
        if (attempt > 1 && idx != node.executorIndexForAttempt(attempt - 1)) {
            s.emit(EventType.FALLBACK_ACTIVATED, node.id(), Map.of(
                    "from", chain.get(node.executorIndexForAttempt(attempt - 1)),
                    "to", executorKey,
                    "attempt", String.valueOf(attempt)));
        }
        s.transition(node.id(), NodeStatus.RUNNING, "attempt " + attempt + " with " + executorKey);
        s.emit(EventType.NODE_ATTEMPT_STARTED, node.id(), Map.of(
                "attempt", String.valueOf(attempt),
                "executor", executorKey,
                "maxAttempts", String.valueOf(node.maxTotalAttempts())));

        Map<String, List<Artifact>> inputs = new LinkedHashMap<>();
        for (String dep : node.dependsOn()) {
            inputs.put(dep, s.state.node(dep).outputHashes().stream()
                    .map(h -> artifacts.get(h).orElseThrow())
                    .toList());
        }
        NodeContext ctx = new NodeContext(s.runId, node, attempt, executorKey, inputs,
                Optional.ofNullable(rt.feedbackForNextAttempt()));
        running.put(node.id(), completions.submit(() -> attempt(ctx)));
    }

    /** Runs on a worker thread. Never throws: every failure becomes an outcome the coordinator records. */
    private AttemptOutcome attempt(NodeContext ctx) {
        TaskNode node = ctx.node();
        try {
            sleeper.sleep(node.retryPolicy().backoffBefore(node.attemptWithinExecutor(ctx.attempt())));
            Optional<FaultSpec> fault = faults.lookup(node.id(), ctx.attempt());
            if (fault.isPresent() && fault.get().kind() == FaultSpec.Kind.EXCEPTION) {
                return AttemptOutcome.failed(ctx, "injected fault: " + fault.get().message(), fault.get());
            }
            NodeResult result = executors.get(ctx.executorKey()).execute(ctx);
            if (result == null) {
                return AttemptOutcome.failed(ctx, "executor returned no result", null);
            }
            GateContext gctx = new GateContext(ctx.runId(), node, ctx.attempt(), result.outputs(), result.workspace());
            List<GateResult> gateResults = new ArrayList<>();
            FaultSpec injected = null;
            for (String gateId : node.exitGates()) {
                Gate gate = gates.find(gateId).orElseThrow();
                GateResult r = gate.evaluate(gctx);
                if (fault.isPresent() && fault.get().kind() == FaultSpec.Kind.GATE_FAILURE
                        && fault.get().gateId().equals(gateId)) {
                    injected = fault.get();
                    r = GateResult.fail(gateId, "injected fault: " + fault.get().message());
                }
                gateResults.add(r);
                if (!r.passed()) {
                    break; // fail fast: later gates would only add noise to the retry feedback
                }
            }
            return AttemptOutcome.completed(ctx, result, gateResults, injected);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AttemptOutcome.failed(ctx, "interrupted", null);
        } catch (ExecutorFailure e) {
            return AttemptOutcome.failed(ctx, e.getMessage(), null).withUsage(e.tokensUsed(), e.costUsd());
        } catch (Exception e) {
            return AttemptOutcome.failed(ctx, e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    private void handle(Session s, AttemptOutcome o) {
        TaskNode node = s.plan.node(o.nodeId());
        if (o.fault() != null) {
            s.emit(EventType.FAULT_INJECTED, node.id(), Map.of(
                    "attempt", String.valueOf(o.attempt()),
                    "kind", o.fault().kind().name(),
                    "message", o.fault().message()));
        }
        if (o.result() != null) {
            recordOutputs(s, node, o);
        }
        if (o.failedTokens() > 0 || o.failedCost() > 0) {
            s.emit(EventType.TOKENS_USED, node.id(), Map.of(
                    "tokens", String.valueOf(o.failedTokens()),
                    "costUsd", String.valueOf(o.failedCost()),
                    "attempt", String.valueOf(o.attempt()),
                    "outcome", "failed"));
        }
        if (o.error() != null) {
            s.emit(EventType.NODE_ATTEMPT_FAILED, node.id(), Map.of(
                    "attempt", String.valueOf(o.attempt()), "reason", o.error()));
            retryOrFail(s, node);
            return;
        }
        s.transition(node.id(), NodeStatus.VERIFYING, "running " + node.exitGates().size() + " exit gate(s)");
        GateResult firstFailure = null;
        for (GateResult g : o.gateResults()) {
            Map<String, String> data = new LinkedHashMap<>(Map.of(
                    "gate", g.gateId(),
                    "phase", "exit",
                    "passed", String.valueOf(g.passed()),
                    "detail", g.detail(),
                    "attempt", String.valueOf(o.attempt())));
            if (g.escalates()) {
                data.put("escalation", g.escalation());
            }
            s.emit(EventType.GATE_EVALUATED, node.id(), data);
            if (!g.passed() && firstFailure == null) {
                firstFailure = g;
            }
        }
        if (firstFailure != null) {
            s.emit(EventType.NODE_ATTEMPT_FAILED, node.id(), Map.of(
                    "attempt", String.valueOf(o.attempt()),
                    "reason", "gate '" + firstFailure.gateId() + "' failed: " + firstFailure.detail()));
            retryOrFail(s, node);
            return;
        }
        NodeRuntime rt = s.state.node(node.id());
        String digest = Hashing.sha256(String.join(",", rt.outputHashes().stream().sorted().toList()));
        List<String> escalations = o.gateResults().stream().filter(GateResult::escalates)
                .map(g -> g.gateId() + ": " + g.escalation()).toList();
        Map<String, String> sealed = new LinkedHashMap<>(Map.of("digest", digest,
                "artifacts", String.valueOf(rt.outputHashes().size())));
        if (!escalations.isEmpty()) {
            sealed.put("escalations", String.join(" | ", escalations));
        }
        s.emit(EventType.NODE_OUTPUT_SEALED, node.id(), sealed);
        if (!rt.approvalRequired() && escalations.isEmpty()) {
            s.transition(node.id(), NodeStatus.SUCCEEDED, "all exit gates passed");
            return;
        }
        if (digest.equals(rt.approvedDigest())) {
            s.transition(node.id(), NodeStatus.SUCCEEDED, "output unchanged since it was approved");
            return;
        }
        if (rt.approvedDigest() != null) {
            s.emit(EventType.APPROVAL_INVALIDATED, node.id(), Map.of("digest", rt.approvedDigest(),
                    "reason", "output changed to " + digest));
        }
        s.transition(node.id(), NodeStatus.AWAITING_APPROVAL, "high-impact output needs a human decision");
        String policyWhy = rt.policyReason() == null ? "risk " + rt.effectiveRisk() : "policy: " + rt.policyReason();
        String why = escalations.isEmpty() ? policyWhy
                : (rt.approvalRequired() ? policyWhy + "; " : "") + "gates: " + String.join("; ", escalations);
        s.emit(EventType.APPROVAL_REQUESTED, node.id(), Map.of("digest", digest,
                "risk", String.valueOf(rt.effectiveRisk()), "reason", why));
        approvalHandler.onApprovalRequested(new ApprovalRequest(s.runId, node, digest, rt.outputHashes(), why))
                .ifPresent(d -> applyDecision(s, node, d));
    }

    private void recordOutputs(Session s, TaskNode node, AttemptOutcome o) {
        List<String> inputHashes = new ArrayList<>();
        node.dependsOn().forEach(d -> inputHashes.addAll(s.state.node(d).outputHashes()));
        for (ArtifactDraft draft : o.result().outputs()) {
            Artifact a = artifacts.put(Artifact.of(draft, new Lineage(s.runId, node.id(), o.attempt(),
                    o.executorKey(), inputHashes, draft.model(), draft.promptHash())));
            s.emit(EventType.ARTIFACT_PRODUCED, node.id(), Map.of(
                    "hash", a.hash(), "name", a.name(), "attempt", String.valueOf(o.attempt()),
                    "executor", o.executorKey(), "inputs", String.join(",", inputHashes)));
        }
        if (o.result().tokensUsed() > 0 || o.result().costUsd() > 0) {
            s.emit(EventType.TOKENS_USED, node.id(), Map.of(
                    "tokens", String.valueOf(o.result().tokensUsed()),
                    "costUsd", String.valueOf(o.result().costUsd())));
        }
        for (DecisionRecord d : o.result().decisions()) {
            s.emit(EventType.DECISION_RECORDED, node.id(), Map.of(
                    "title", d.title(), "context", d.context(), "options", String.join(" | ", d.options()),
                    "choice", d.choice(), "rationale", d.rationale()));
        }
    }

    private void retryOrFail(Session s, TaskNode node) {
        NodeRuntime rt = s.state.node(node.id());
        if (rt.attempts() < node.maxTotalAttempts()) {
            s.transition(node.id(), NodeStatus.RETRYING, "attempt " + rt.attempts() + " of "
                    + node.maxTotalAttempts() + " failed; retrying with the failure as feedback");
        } else {
            s.transition(node.id(), NodeStatus.FAILED, "exhausted " + node.maxTotalAttempts()
                    + " attempt(s) across " + node.executorChain().size() + " executor(s)");
            compensate(s, node, "node failed");
        }
    }

    private void applyDecision(Session s, TaskNode node, ApprovalDecision d) {
        NodeRuntime rt = s.state.node(node.id());
        if (rt.status() != NodeStatus.AWAITING_APPROVAL) {
            throw new IllegalStateException("node " + node.id() + " is " + rt.status() + ", not awaiting approval");
        }
        if (!d.outputDigest().equals(rt.outputDigest())) {
            throw new ApprovalMismatchException("decision is for output " + d.outputDigest()
                    + " but node " + node.id() + " currently has output " + rt.outputDigest());
        }
        if (d.approved()) {
            s.emit(EventType.APPROVAL_GRANTED, node.id(), Map.of("approver", d.approver(),
                    "digest", d.outputDigest(), "comment", d.comment()));
            s.transition(node.id(), NodeStatus.SUCCEEDED, "approved by " + d.approver());
            return;
        }
        s.emit(EventType.APPROVAL_REJECTED, node.id(), Map.of("approver", d.approver(),
                "digest", d.outputDigest(), "feedback", d.comment()));
        if (rt.attempts() < node.maxTotalAttempts()) {
            s.transition(node.id(), NodeStatus.RETRYING, "rejected; retrying with reviewer feedback");
        } else {
            s.transition(node.id(), NodeStatus.FAILED, "rejected and no attempts left");
            compensate(s, node, "rejected by " + d.approver());
        }
    }

    private void compensate(Session s, TaskNode node, String reason) {
        NodeRuntime rt = s.state.node(node.id());
        String executorKey = rt.currentExecutor() != null ? rt.currentExecutor() : node.executorKey();
        try {
            String detail = executors.get(executorKey).compensate(
                    new CompensationContext(s.runId, node, rt.outputHashes(), reason));
            s.emit(EventType.COMPENSATION_EXECUTED, node.id(), Map.of("executor", executorKey,
                    "reason", reason, "detail", detail == null ? "" : detail));
        } catch (Exception e) {
            s.emit(EventType.COMPENSATION_FAILED, node.id(), Map.of("executor", executorKey,
                    "reason", reason, "error", String.valueOf(e.getMessage())));
            control(s.runId).stopReason = "compensation failed for '" + node.id() + "'; manual cleanup needed";
        }
    }

    private Optional<RunOutcome> settle(Session s) {
        RunState st = s.state;
        if (st.allNodes(NodeStatus.SUCCEEDED)) {
            s.emit(EventType.RUN_SUCCEEDED, null, Map.of("reason", "all nodes succeeded"));
            return Optional.of(s.outcome());
        }
        boolean awaiting = st.anyNode(NodeStatus.AWAITING_APPROVAL);
        boolean schedulable = s.plan.topologicalOrder().stream().anyMatch(id -> {
            NodeStatus ns = st.node(id).status();
            if (ns == NodeStatus.RETRYING) {
                return true;
            }
            return ns == NodeStatus.PENDING && s.plan.node(id).dependsOn().stream()
                    .allMatch(d -> st.node(d).status() == NodeStatus.SUCCEEDED);
        });
        if (schedulable) {
            return Optional.empty();
        }
        if (awaiting) {
            if (st.status() != RunStatus.PAUSED_FOR_APPROVAL) {
                List<String> waiting = s.plan.topologicalOrder().stream()
                        .filter(id -> st.node(id).status() == NodeStatus.AWAITING_APPROVAL).toList();
                s.emit(EventType.RUN_PAUSED, null, Map.of("reason", "waiting for approval: " + waiting));
            }
            return Optional.of(s.outcome());
        }
        boolean blockedPending = s.plan.topologicalOrder().stream()
                .anyMatch(id -> st.node(id).status() == NodeStatus.PENDING);
        if (blockedPending) {
            return Optional.empty(); // the next schedule pass will skip them
        }
        List<String> failed = s.plan.topologicalOrder().stream()
                .filter(id -> st.node(id).status() == NodeStatus.FAILED).toList();
        if (options.rollbackOnFailure()) {
            List<String> order = new ArrayList<>(s.plan.topologicalOrder());
            java.util.Collections.reverse(order);
            for (String id : order) {
                if (st.node(id).status() == NodeStatus.SUCCEEDED) {
                    compensate(s, s.plan.node(id), "run failed at " + failed);
                    s.transition(id, NodeStatus.ROLLED_BACK, "run rollback after failure of " + failed);
                }
            }
            s.emit(EventType.RUN_ROLLED_BACK, null, Map.of("reason", "failed nodes " + failed));
        } else {
            s.emit(EventType.RUN_FAILED, null, Map.of("reason", "failed nodes " + failed));
        }
        return Optional.of(s.outcome());
    }

    private String stopReason(RunControl ctl, Session s) {
        if (ctl.stopReason != null) {
            return ctl.stopReason;
        }
        RunBudget budget = plans.load(s.runId).budget();
        RunState st = s.state;
        if (st.tokensUsed() > budget.maxTokens()) {
            return budgetExceeded(s, "tokens", st.tokensUsed(), budget.maxTokens());
        }
        if (st.costUsd() > budget.maxCostUsd()) {
            return budgetExceeded(s, "costUsd", st.costUsd(), budget.maxCostUsd());
        }
        if (st.activeSince() != null) {
            Duration elapsed = Duration.between(st.activeSince(), clock.instant());
            if (elapsed.compareTo(budget.maxWallTime()) > 0) {
                return budgetExceeded(s, "wallTime", elapsed.toMillis(), budget.maxWallTime().toMillis());
            }
        }
        return null;
    }

    private String budgetExceeded(Session s, String kind, Number used, Number limit) {
        s.emit(EventType.BUDGET_EXCEEDED, null, Map.of("kind", kind, "used", String.valueOf(used),
                "limit", String.valueOf(limit)));
        return "budget exceeded: " + kind + " " + used + " > " + limit;
    }

    private void safeStop(Session s, Map<String, Future<AttemptOutcome>> running, String reason) {
        running.values().forEach(f -> f.cancel(true));
        for (String id : s.plan.topologicalOrder()) {
            NodeStatus st = s.state.node(id).status();
            if (st == NodeStatus.READY || st == NodeStatus.RUNNING || st == NodeStatus.VERIFYING
                    || st == NodeStatus.RETRYING) {
                s.transition(id, NodeStatus.CANCELLED, "safe-stop: " + reason);
            }
        }
        running.clear();
        s.emit(EventType.RUN_STOPPED, null, Map.of("reason", reason));
    }

    // ------------------------------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------------------------------

    private void validateBindings(Plan plan) {
        List<String> errors = new ArrayList<>();
        for (TaskNode n : plan.nodes().values()) {
            for (String key : n.executorChain()) {
                if (executors.find(key).isEmpty()) {
                    errors.add("node '" + n.id() + "' uses unknown executor '" + key + "'");
                }
            }
            for (String gate : n.exitGates()) {
                if (gates.find(gate).isEmpty()) {
                    errors.add("node '" + n.id() + "' uses unknown gate '" + gate + "'");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new PlanValidationException(errors);
        }
    }

    private RunControl control(String runId) {
        return controls.computeIfAbsent(runId, k -> new RunControl());
    }

    private Session load(String runId) {
        Plan plan = plans.load(runId).plan();
        return new Session(runId, plan, RunState.rebuild(runId, plan, events.events(runId)));
    }

    private void withSession(String runId, Consumer<Session> action) {
        RunControl ctl = control(runId);
        synchronized (ctl) {
            if (ctl.active) {
                ctl.commands.add(action);
                return;
            }
            action.accept(load(runId));
        }
    }

    private void drainCommands(RunControl ctl, Session s) {
        Consumer<Session> cmd;
        while ((cmd = ctl.commands.poll()) != null) {
            cmd.accept(s);
        }
    }

    private static final class RunControl {
        volatile boolean active;
        volatile String stopReason;
        final Queue<Consumer<Session>> commands = new ConcurrentLinkedQueue<>();
    }

    /** The only path by which state changes: append an event, then apply it to the projection. */
    private final class Session {
        final String runId;
        final Plan plan;
        final RunState state;

        Session(String runId, Plan plan) {
            this(runId, plan, new RunState(runId, plan));
        }

        Session(String runId, Plan plan, RunState state) {
            this.runId = runId;
            this.plan = plan;
            this.state = state;
        }

        void emit(EventType type, String nodeId, Map<String, String> data) {
            state.apply(events.append(runId, type, nodeId, data, clock.instant()));
        }

        void transition(String nodeId, NodeStatus to, String reason) {
            NodeStatus from = state.node(nodeId).status();
            if (!from.canTransitionTo(to)) {
                throw new IllegalStateException("illegal transition for '" + nodeId + "': " + from + " -> " + to);
            }
            emit(EventType.NODE_STATUS_CHANGED, nodeId, Map.of("from", from.name(), "to", to.name(),
                    "reason", reason));
        }

        RunOutcome outcome() {
            return new RunOutcome(runId, state.status(), state.statusReason(), state.nodeStatuses());
        }
    }

    private record AttemptOutcome(String nodeId, int attempt, String executorKey, NodeResult result,
                                  List<GateResult> gateResults, String error, FaultSpec fault,
                                  long failedTokens, double failedCost) {
        static AttemptOutcome completed(NodeContext c, NodeResult r, List<GateResult> gates, FaultSpec fault) {
            return new AttemptOutcome(c.node().id(), c.attempt(), c.executorKey(), r, List.copyOf(gates), null, fault,
                    0, 0);
        }

        static AttemptOutcome failed(NodeContext c, String error, FaultSpec fault) {
            return new AttemptOutcome(c.node().id(), c.attempt(), c.executorKey(), null, List.of(), error, fault, 0, 0);
        }

        AttemptOutcome withUsage(long tokens, double cost) {
            return new AttemptOutcome(nodeId, attempt, executorKey, result, gateResults, error, fault, tokens, cost);
        }
    }

    public static final class Builder {
        private EventStore events;
        private ArtifactStore artifacts;
        private ExecutorRegistry executors;
        private GateRegistry gates = GateRegistry.withBuiltIns();
        private PolicyEngine policy;
        private PlanStore plans = new PlanStore.InMemory();
        private ApprovalHandler approvalHandler = ApprovalHandler.MANUAL;
        private FaultInjector faults = FaultInjector.NONE;
        private Sleeper sleeper = Sleeper.REAL;
        private Clock clock = Clock.systemUTC();
        private EngineOptions options = EngineOptions.DEFAULT;
        private Supplier<String> runIdSupplier = () -> "run-" + UUID.randomUUID().toString().substring(0, 8);

        public Builder events(EventStore v) { this.events = v; return this; }
        public Builder artifacts(ArtifactStore v) { this.artifacts = v; return this; }
        public Builder executors(ExecutorRegistry v) { this.executors = v; return this; }
        public Builder gates(GateRegistry v) { this.gates = v; return this; }
        public Builder policy(PolicyEngine v) { this.policy = v; return this; }
        public Builder plans(PlanStore v) { this.plans = v; return this; }
        public Builder approvals(ApprovalHandler v) { this.approvalHandler = v; return this; }
        public Builder faults(FaultInjector v) { this.faults = v; return this; }
        public Builder sleeper(Sleeper v) { this.sleeper = v; return this; }
        public Builder clock(Clock v) { this.clock = v; return this; }
        public Builder options(EngineOptions v) { this.options = v; return this; }
        public Builder runIds(Supplier<String> v) { this.runIdSupplier = v; return this; }

        public Engine build() {
            return new Engine(this);
        }
    }
}
