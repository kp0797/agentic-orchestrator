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

    /**
     * Parses a command-line fault: {@code node:exception[@1,2]} or {@code node:gate:<gateId>[@1]}.
     * Attempts default to the first one.
     */
    public static FaultSpec parse(String text) {
        String spec = text.strip();
        java.util.Set<Integer> attempts = java.util.Set.of(1);
        int at = spec.indexOf('@');
        if (at >= 0) {
            attempts = java.util.Arrays.stream(spec.substring(at + 1).split(",")).map(String::strip)
                    .map(Integer::valueOf).collect(java.util.stream.Collectors.toSet());
            spec = spec.substring(0, at);
        }
        String[] parts = spec.split(":");
        String usage = "fault '" + text + "': use node:exception[@attempts] or node:gate:<gate-id>[@attempts]";
        if (parts.length < 2 || parts[0].isBlank()) {
            throw new IllegalArgumentException(usage);
        }
        String message = "injected from the command line";
        return switch (parts[1].toLowerCase(java.util.Locale.ROOT)) {
            case "exception" -> new FaultSpec(parts[0], Kind.EXCEPTION, attempts, null, message);
            case "gate" -> {
                if (parts.length != 3 || parts[2].isBlank()) throw new IllegalArgumentException(usage);
                yield new FaultSpec(parts[0], Kind.GATE_FAILURE, attempts, parts[2], message);
            }
            default -> throw new IllegalArgumentException(usage);
        };
    }
}
