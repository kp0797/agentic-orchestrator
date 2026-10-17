package com.agentic.orchestrator.gates;

/** Outcome of one gate. {@code detail} is fed back to the executor on a retry when it fails. */
public record GateResult(String gateId, boolean passed, String detail) {
    public static GateResult pass(String gateId, String detail) {
        return new GateResult(gateId, true, detail);
    }

    public static GateResult fail(String gateId, String detail) {
        return new GateResult(gateId, false, detail);
    }
}
