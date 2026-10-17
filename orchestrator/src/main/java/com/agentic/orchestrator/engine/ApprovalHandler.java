package com.agentic.orchestrator.engine;

import java.util.Optional;

/**
 * Called when a node needs approval. Returning empty pauses that node until a human decides through
 * {@link Engine#decide}. The auto-approver exists only for unattended demos and labels itself as such
 * in the audit log.
 */
@FunctionalInterface
public interface ApprovalHandler {
    ApprovalHandler MANUAL = request -> Optional.empty();

    Optional<ApprovalDecision> onApprovalRequested(ApprovalRequest request);

    static ApprovalHandler autoApprove(String label) {
        return request -> Optional.of(ApprovalDecision.approve("auto-approver:" + label, request.outputDigest(),
                "automatic approval for an unattended demo, not a human review"));
    }
}
