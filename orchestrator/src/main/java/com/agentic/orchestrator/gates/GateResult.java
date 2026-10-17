package com.agentic.orchestrator.gates;

/**
 * Outcome of one gate. {@code detail} is fed back to the executor on a retry when it fails.
 *
 * @param escalation when non-null, the gate passed but the change needs a human decision
 *                   (for example a schema migration or a new dependency); the reason is shown
 *                   to the approver
 */
public record GateResult(String gateId, boolean passed, String detail, String escalation) {

    public static GateResult pass(String gateId, String detail) {
        return new GateResult(gateId, true, detail, null);
    }

    public static GateResult fail(String gateId, String detail) {
        return new GateResult(gateId, false, detail, null);
    }

    public static GateResult escalate(String gateId, String detail, String reason) {
        return new GateResult(gateId, true, detail, reason);
    }

    public boolean escalates() {
        return passed && escalation != null;
    }
}
