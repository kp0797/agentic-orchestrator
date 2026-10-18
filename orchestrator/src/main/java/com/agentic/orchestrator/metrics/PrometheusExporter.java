package com.agentic.orchestrator.metrics;

import com.agentic.orchestrator.domain.Plan;
import com.agentic.orchestrator.events.AuditVerifier;
import com.agentic.orchestrator.events.Event;
import com.agentic.orchestrator.events.EventStore;
import com.agentic.orchestrator.events.EventType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Orchestrator metrics in the Prometheus text exposition format (0.0.4), computed from the audit log
 * at scrape time. Like {@link RunMetrics}, they cannot drift from what the log says happened, and
 * they need no metrics library. Counters cover every run the store holds since the process started.
 *
 * <p>Trade-off: recomputing from the log is O(events) per scrape, fine for this system's volume; a
 * production deployment would keep incremental counters (Micrometer) fed by the same events.
 */
public final class PrometheusExporter {
    private final EventStore events;
    private final Function<String, Optional<Plan>> plans;

    public PrometheusExporter(EventStore events, Function<String, Optional<Plan>> plans) {
        this.events = events;
        this.plans = plans;
    }

    public String render() {
        Map<String, Long> runsByStatus = new TreeMap<>();
        Map<String, Long> attempts = new TreeMap<>();
        Map<String, Long> attemptFailures = new TreeMap<>();
        Map<String, Long> gates = new TreeMap<>();
        Map<String, Long> approvals = new TreeMap<>();
        long retries = 0, fallbacks = 0, rollbacks = 0, compensations = 0, faults = 0, recovered = 0;
        long clarifications = 0, replans = 0, pending = 0, tokens = 0, auditEvents = 0, brokenChains = 0;
        double cost = 0, recoverySecondsSum = 0;
        long recoveryCount = 0;

        for (String runId : events.runIds()) {
            List<Event> log = events.events(runId);
            if (log.isEmpty()) {
                continue;
            }
            RunMetrics m = RunMetrics.from(log);
            Optional<Plan> plan = plans.apply(runId);
            runsByStatus.merge(m.finalStatus(), 1L, Long::sum);
            retries += m.retries();
            fallbacks += m.fallbacks();
            rollbacks += m.rollbacks();
            compensations += m.compensations();
            faults += m.faultsInjected();
            recovered += m.recoveredNodes();
            tokens += m.tokens();
            cost += m.costUsd();
            pending += m.nodeStatuses().values().stream().filter("AWAITING_APPROVAL"::equals).count();
            if (m.mttr().isPresent() && m.recoveredNodes() > 0) {
                recoverySecondsSum += m.mttr().get().toMillis() / 1000.0 * m.recoveredNodes();
                recoveryCount += m.recoveredNodes();
            }
            auditEvents += log.size();
            if (!AuditVerifier.verify(log).valid()) {
                brokenChains++;
            }
            for (Event e : log) {
                switch (e.type()) {
                    case NODE_ATTEMPT_STARTED -> attempts.merge(label("node_type", type(plan, e)), 1L, Long::sum);
                    case NODE_ATTEMPT_FAILED -> attemptFailures.merge(label("node_type", type(plan, e)), 1L, Long::sum);
                    case GATE_EVALUATED -> gates.merge(label("gate", e.get("gate")) + ",result=\""
                            + ("true".equals(e.get("passed")) ? "pass" : "fail") + "\"", 1L, Long::sum);
                    case APPROVAL_GRANTED -> approvals.merge("decision=\"granted\"", 1L, Long::sum);
                    case APPROVAL_REJECTED -> approvals.merge("decision=\"rejected\"", 1L, Long::sum);
                    case CLARIFICATION_RECORDED -> clarifications++;
                    case RUN_STOPPED -> {
                        if (String.valueOf(e.get("reason")).startsWith("superseded")) {
                            replans++;
                        }
                    }
                    default -> { }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        family(sb, "agentic_runs", "gauge", "Engine runs (planning and execution) by current status.");
        runsByStatus.forEach((status, n) -> sample(sb, "agentic_runs", "status=\"" + status + "\"", n));
        family(sb, "agentic_node_attempts_total", "counter", "Node attempts started, by node type.");
        attempts.forEach((l, n) -> sample(sb, "agentic_node_attempts_total", l, n));
        family(sb, "agentic_node_attempt_failures_total", "counter", "Node attempts that failed, by node type.");
        attemptFailures.forEach((l, n) -> sample(sb, "agentic_node_attempt_failures_total", l, n));
        family(sb, "agentic_gate_evaluations_total", "counter", "Exit gate evaluations by gate and result.");
        gates.forEach((l, n) -> sample(sb, "agentic_gate_evaluations_total", l, n));
        family(sb, "agentic_approvals_total", "counter", "Human (or labelled automatic) approval decisions.");
        approvals.forEach((l, n) -> sample(sb, "agentic_approvals_total", l, n));
        single(sb, "agentic_approvals_pending", "gauge", "Nodes waiting for a human decision now.", pending);
        single(sb, "agentic_retries_total", "counter", "Node retries after a failed attempt.", retries);
        single(sb, "agentic_fallbacks_total", "counter", "Switches to a fallback executor.", fallbacks);
        single(sb, "agentic_rollbacks_total", "counter", "Nodes rolled back.", rollbacks);
        single(sb, "agentic_compensations_total", "counter", "Compensation actions executed.", compensations);
        single(sb, "agentic_faults_injected_total", "counter", "Deliberately injected faults.", faults);
        single(sb, "agentic_recovered_nodes_total", "counter", "Nodes that failed an attempt and then succeeded.",
                recovered);
        family(sb, "agentic_node_recovery_seconds", "summary",
                "Time from a node's first failed attempt to its success.");
        sb.append("agentic_node_recovery_seconds_sum ").append(num(recoverySecondsSum)).append('\n');
        sb.append("agentic_node_recovery_seconds_count ").append(recoveryCount).append('\n');
        single(sb, "agentic_clarifications_total", "counter", "Clarifications recorded.", clarifications);
        single(sb, "agentic_replans_total", "counter", "Execution runs superseded by a re-plan.", replans);
        single(sb, "agentic_model_tokens_total", "counter", "Model tokens used (0 in replay).", tokens);
        family(sb, "agentic_model_cost_usd_total", "counter", "Model cost in US dollars (0 in replay).");
        sb.append("agentic_model_cost_usd_total ").append(num(cost)).append('\n');
        single(sb, "agentic_audit_events_total", "counter", "Events in all audit logs.", auditEvents);
        single(sb, "agentic_audit_chains_broken", "gauge", "Runs whose audit hash chain does not verify.",
                brokenChains);
        return sb.toString();
    }

    private static String type(Optional<Plan> plan, Event e) {
        return plan.flatMap(p -> {
            try {
                return Optional.of(p.node(e.nodeId()).type().name());
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }).orElse("UNKNOWN");
    }

    private static String label(String name, String value) {
        String v = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return name + "=\"" + v + "\"";
    }

    private static void family(StringBuilder sb, String name, String type, String help) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static void single(StringBuilder sb, String name, String type, String help, long value) {
        family(sb, name, type, help);
        sb.append(name).append(' ').append(value).append('\n');
    }

    private static void sample(StringBuilder sb, String name, String labels, long value) {
        sb.append(name).append('{').append(labels).append("} ").append(value).append('\n');
    }

    private static String num(double d) {
        return String.format(Locale.ROOT, "%.6f", d);
    }
}
