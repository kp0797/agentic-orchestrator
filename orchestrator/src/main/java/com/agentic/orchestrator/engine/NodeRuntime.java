package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.domain.NodeStatus;

import java.util.ArrayList;
import java.util.List;

/** Mutable per-node projection of the event log. Only {@link RunState#apply} changes it. */
public final class NodeRuntime {
    private final String nodeId;
    NodeStatus status = NodeStatus.PENDING;
    int attempts;
    String currentExecutor;
    String lastFailure;
    String reviewerFeedback;
    final List<String> outputHashes = new ArrayList<>();
    String outputDigest;
    String approvedDigest;
    boolean approvalRequired;
    String effectiveRisk;
    String policyReason;

    NodeRuntime(String nodeId) {
        this.nodeId = nodeId;
    }

    void reset() {
        attempts = 0;
        currentExecutor = null;
        lastFailure = null;
        reviewerFeedback = null;
        outputHashes.clear();
        outputDigest = null;
        approvedDigest = null;
    }

    public String nodeId() { return nodeId; }
    public NodeStatus status() { return status; }
    public int attempts() { return attempts; }
    public String currentExecutor() { return currentExecutor; }
    public String lastFailure() { return lastFailure; }
    public String reviewerFeedback() { return reviewerFeedback; }

    /**
     * What the next attempt is told. A human reviewer's feedback stays in force for every later
     * attempt of this node; the latest gate or error output is added to it, not substituted for it.
     */
    public String feedbackForNextAttempt() {
        if (reviewerFeedback == null) {
            return lastFailure;
        }
        if (lastFailure == null || lastFailure.startsWith("rejected by ")) {
            return "Reviewer feedback (still applies): " + reviewerFeedback;
        }
        return "Reviewer feedback (still applies): " + reviewerFeedback + "\nLatest failure: " + lastFailure;
    }
    public List<String> outputHashes() { return List.copyOf(outputHashes); }
    public String outputDigest() { return outputDigest; }
    public String approvedDigest() { return approvedDigest; }
    public boolean approvalRequired() { return approvalRequired; }
    public String effectiveRisk() { return effectiveRisk; }
    public String policyReason() { return policyReason; }
}
