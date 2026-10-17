package com.agentic.orchestrator.policy;

import com.agentic.orchestrator.domain.RiskLevel;

/**
 * Policy verdict for a node before it runs.
 *
 * @param effectiveRisk the risk policy assigns; it can raise the planner's value, never lower it
 * @param critical      a denial that should safe-stop the whole run rather than fail one node
 */
public record PolicyDecision(Verdict verdict, RiskLevel effectiveRisk, boolean approvalRequired,
                             boolean critical, String reason) {
    public enum Verdict { ALLOW, DENY }

    public static PolicyDecision allow(RiskLevel risk, boolean approvalRequired, String reason) {
        return new PolicyDecision(Verdict.ALLOW, risk, approvalRequired, false, reason);
    }

    public static PolicyDecision deny(RiskLevel risk, boolean critical, String reason) {
        return new PolicyDecision(Verdict.DENY, risk, false, critical, reason);
    }

    public boolean allowed() {
        return verdict == Verdict.ALLOW;
    }
}
