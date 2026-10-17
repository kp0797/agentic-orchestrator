package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.domain.TaskNode;

import java.util.List;

/** Shown to the approver: the exact output digest being approved and why approval is needed. */
public record ApprovalRequest(String runId, TaskNode node, String outputDigest, List<String> artifactHashes,
                              String reason) {
    public ApprovalRequest {
        artifactHashes = List.copyOf(artifactHashes);
    }
}
