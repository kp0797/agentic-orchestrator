package com.agentic.orchestrator.demo;

import com.agentic.orchestrator.events.Event;
import com.agentic.orchestrator.events.EventType;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Compact, human-readable timeline of a run's audit log. */
public final class EventPrinter {
    private static final Set<EventType> QUIET = Set.of(EventType.TOKENS_USED, EventType.ARTIFACT_PRODUCED,
            EventType.POLICY_EVALUATED, EventType.NODE_OUTPUT_SEALED);

    private EventPrinter() {}

    public static void print(List<Event> events, PrintStream out, boolean verbose) {
        if (events.isEmpty()) {
            return;
        }
        Instant t0 = events.get(0).timestamp();
        for (Event e : events) {
            if (!verbose && QUIET.contains(e.type())) {
                continue;
            }
            long ms = Duration.between(t0, e.timestamp()).toMillis();
            out.printf("  %5d  +%5dms  %-18s %-22s %s%n", e.seq(), ms, e.nodeId().isEmpty() ? "-" : e.nodeId(),
                    e.type(), describe(e));
        }
    }

    public static String describe(Event e) {
        return switch (e.type()) {
            case NODE_STATUS_CHANGED -> e.get("from") + " -> " + e.get("to") + "  (" + e.get("reason") + ")";
            case NODE_ATTEMPT_STARTED -> "attempt " + e.get("attempt") + "/" + e.get("maxAttempts") + " via "
                    + e.get("executor");
            case NODE_ATTEMPT_FAILED -> "attempt " + e.get("attempt") + ": " + e.get("reason");
            case GATE_EVALUATED -> e.get("gate") + " " + ("true".equals(e.get("passed")) ? "PASS" : "FAIL")
                    + " - " + e.get("detail");
            case APPROVAL_REQUESTED -> "digest " + e.get("digest").substring(0, 12) + " (risk " + e.get("risk") + ")";
            case APPROVAL_GRANTED -> "by " + e.get("approver") + " for digest " + e.get("digest").substring(0, 12);
            case APPROVAL_REJECTED -> "by " + e.get("approver") + ": " + e.get("feedback");
            case DECISION_RECORDED -> e.get("title") + " -> " + e.get("choice");
            default -> e.data().isEmpty() ? "" : e.data().toString();
        };
    }
}
