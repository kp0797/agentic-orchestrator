package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.domain.NodeStatus;
import com.agentic.orchestrator.domain.Plan;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.events.Event;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run state as a pure function of the event log. The engine changes state only by appending an event
 * and applying it here, so rebuilding from the log after a crash gives exactly the same state.
 */
public final class RunState {
    private final String runId;
    private final Plan plan;
    private final Map<String, NodeRuntime> nodes = new LinkedHashMap<>();
    private RunStatus status = RunStatus.CREATED;
    private String statusReason = "";
    private long tokensUsed;
    private double costUsd;
    private Instant activeSince;
    private long lastSeq;

    public RunState(String runId, Plan plan) {
        this.runId = runId;
        this.plan = plan;
        plan.topologicalOrder().forEach(id -> nodes.put(id, new NodeRuntime(id)));
    }

    public static RunState rebuild(String runId, Plan plan, List<Event> events) {
        RunState state = new RunState(runId, plan);
        events.forEach(state::apply);
        return state;
    }

    public void apply(Event e) {
        if (!e.runId().equals(runId)) {
            throw new IllegalArgumentException("event belongs to run " + e.runId());
        }
        if (e.seq() != lastSeq + 1) {
            throw new IllegalStateException("event seq " + e.seq() + " applied out of order after " + lastSeq);
        }
        lastSeq = e.seq();
        NodeRuntime n = e.hasNode() ? node(e.nodeId()) : null;
        switch (e.type()) {
            case RUN_STARTED, RUN_RESUMED -> {
                status = RunStatus.RUNNING;
                statusReason = "";
                activeSince = e.timestamp();
            }
            case RUN_PAUSED -> setStatus(RunStatus.PAUSED_FOR_APPROVAL, e);
            case RUN_STOPPED -> setStatus(RunStatus.STOPPED, e);
            case RUN_SUCCEEDED -> setStatus(RunStatus.SUCCEEDED, e);
            case RUN_FAILED -> setStatus(RunStatus.FAILED, e);
            case RUN_ROLLED_BACK -> setStatus(RunStatus.ROLLED_BACK, e);
            case NODE_STATUS_CHANGED -> n.status = NodeStatus.valueOf(e.get("to"));
            case NODE_RESET -> {
                n.reset();
                n.lastFailure = e.get("feedback");
            }
            case POLICY_EVALUATED -> {
                n.approvalRequired = Boolean.parseBoolean(e.get("approvalRequired"));
                n.effectiveRisk = e.get("risk");
                n.policyReason = e.get("reason");
            }
            case NODE_ATTEMPT_STARTED -> {
                n.attempts = Integer.parseInt(e.get("attempt"));
                n.currentExecutor = e.get("executor");
                n.outputHashes.clear();
                n.outputDigest = null;
            }
            case NODE_ATTEMPT_FAILED -> n.lastFailure = e.get("reason");
            case ARTIFACT_PRODUCED -> n.outputHashes.add(e.get("hash"));
            case NODE_OUTPUT_SEALED -> n.outputDigest = e.get("digest");
            case APPROVAL_GRANTED -> n.approvedDigest = e.get("digest");
            case APPROVAL_INVALIDATED -> n.approvedDigest = null;
            case APPROVAL_REJECTED -> {
                n.lastFailure = "rejected by " + e.get("approver") + ": " + e.get("feedback");
                n.reviewerFeedback = n.reviewerFeedback == null ? e.get("feedback")
                        : n.reviewerFeedback + "\n" + e.get("feedback");
            }
            case TOKENS_USED -> {
                tokensUsed += Long.parseLong(e.get("tokens"));
                costUsd += Double.parseDouble(e.get("costUsd"));
            }
            default -> { /* informational: gates, decisions, faults, fallbacks, compensations */ }
        }
    }

    private void setStatus(RunStatus s, Event e) {
        status = s;
        statusReason = e.data().getOrDefault("reason", "");
    }

    public NodeRuntime node(String id) {
        NodeRuntime n = nodes.get(id);
        if (n == null) {
            throw new IllegalArgumentException("unknown node " + id);
        }
        return n;
    }

    public Map<String, NodeStatus> nodeStatuses() {
        Map<String, NodeStatus> m = new LinkedHashMap<>();
        nodes.forEach((k, v) -> m.put(k, v.status));
        return Collections.unmodifiableMap(m);
    }

    public boolean allNodes(NodeStatus s) {
        return nodes.values().stream().allMatch(n -> n.status == s);
    }

    public boolean anyNode(NodeStatus s) {
        return nodes.values().stream().anyMatch(n -> n.status == s);
    }

    public String runId() { return runId; }
    public Plan plan() { return plan; }
    public RunStatus status() { return status; }
    public String statusReason() { return statusReason; }
    public long tokensUsed() { return tokensUsed; }
    public double costUsd() { return costUsd; }
    public Instant activeSince() { return activeSince; }
    public long lastSeq() { return lastSeq; }
}
