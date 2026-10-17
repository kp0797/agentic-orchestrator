package com.agentic.orchestrator.engine;

import java.util.Set;

/**
 * A deliberate failure, used to demonstrate retries, fallback, rollback and safe-stop on demand.
 * Every injected fault is recorded in the audit log, so a demo can never pass one off as real.
 */
public record FaultSpec(String nodeId, Kind kind, Set<Integer> attempts, String gateId, String message) {
    public enum Kind {
        /** The executor throws. */
        EXCEPTION,
        /** The named exit gate fails even if the output is fine. */
        GATE_FAILURE
    }

    public FaultSpec {
        attempts = Set.copyOf(attempts);
        if (kind == Kind.GATE_FAILURE && (gateId == null || gateId.isBlank())) {
            throw new IllegalArgumentException("GATE_FAILURE needs a gate id");
        }
    }

    public static FaultSpec exception(String nodeId, String message, Integer... attempts) {
        return new FaultSpec(nodeId, Kind.EXCEPTION, Set.of(attempts), null, message);
    }

    public static FaultSpec gateFailure(String nodeId, String gateId, String message, Integer... attempts) {
        return new FaultSpec(nodeId, Kind.GATE_FAILURE, Set.of(attempts), gateId, message);
    }
}
