package com.agentic.orchestrator.engine;

import java.util.Objects;

/**
 * A human decision bound to an output digest. Approving a digest other than the node's current
 * output is refused, so an approval can never carry over to changed work.
 */
public record ApprovalDecision(boolean approved, String approver, String outputDigest, String comment) {
    public ApprovalDecision {
        Objects.requireNonNull(approver, "approver");
        Objects.requireNonNull(outputDigest, "outputDigest");
        comment = comment == null ? "" : comment;
    }

    public static ApprovalDecision approve(String approver, String digest, String comment) {
        return new ApprovalDecision(true, approver, digest, comment);
    }

    public static ApprovalDecision reject(String approver, String digest, String feedback) {
        return new ApprovalDecision(false, approver, digest, feedback);
    }
}
