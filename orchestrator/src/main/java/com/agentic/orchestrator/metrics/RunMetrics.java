package com.agentic.orchestrator.metrics;

import com.agentic.orchestrator.events.Event;
import com.agentic.orchestrator.events.EventType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reliability metrics computed from the event log alone. Because they are derived from the same
 * hash-chained log that drives the engine, the numbers cannot drift from what actually happened.
 *
 * <p>MTTR is measured per node from its first failed attempt to its next success, reported with and
 * without time spent waiting for a human.
 */
public record RunMetrics(
        String runId,
        String finalStatus,
        int nodes,
        Map<String, String> nodeStatuses,
        int attempts,
        int failedAttempts,
        int retries,
        int fallbacks,
        int rollbacks,
        int compensations,
        int faultsInjected,
        int approvalsRequested,
        int approvalsGranted,
        int approvalsRejected,
        int recoveredNodes,
        Duration endToEnd,
        Duration humanWait,
        Optional<Duration> mttr,
        Optional<Duration> mttrExcludingHumanWait,
        long tokens,
        double costUsd) {

    public double attemptSuccessRate() {
        return attempts == 0 ? 0.0 : (attempts - failedAttempts) / (double) attempts;
    }

    public static RunMetrics from(List<Event> events) {
        if (events.isEmpty()) {
            throw new IllegalArgumentException("no events");
        }
        String runId = events.get(0).runId();
        String finalStatus = "RUNNING";
        int nodes = 0;
        Map<String, String> statuses = new LinkedHashMap<>();
        int attempts = 0, failed = 0, retries = 0, fallbacks = 0, rollbacks = 0, compensations = 0, faults = 0;
        int requested = 0, granted = 0, rejected = 0;
        long tokens = 0;
        double cost = 0;
        Instant start = events.get(0).timestamp();
        Instant end = events.get(events.size() - 1).timestamp();

        Map<String, Instant> firstFailure = new HashMap<>();
        Map<String, Instant> approvalOpened = new HashMap<>();
        Map<String, List<Duration[]>> waits = new HashMap<>(); // node -> [startEpochMs, endEpochMs]
        List<Duration> repairTimes = new ArrayList<>();
        List<Duration> repairTimesNoWait = new ArrayList<>();
        Duration humanWait = Duration.ZERO;

        for (Event e : events) {
            switch (e.type()) {
                case PLAN_ACCEPTED -> nodes = Integer.parseInt(e.get("nodes"));
                case NODE_ATTEMPT_STARTED -> attempts++;
                case NODE_ATTEMPT_FAILED -> {
                    failed++;
                    firstFailure.putIfAbsent(e.nodeId(), e.timestamp());
                }
                case FALLBACK_ACTIVATED -> fallbacks++;
                case COMPENSATION_EXECUTED -> compensations++;
                case FAULT_INJECTED -> faults++;
                case TOKENS_USED -> {
                    tokens += Long.parseLong(e.get("tokens"));
                    cost += Double.parseDouble(e.get("costUsd"));
                }
                case APPROVAL_REQUESTED -> {
                    requested++;
                    approvalOpened.put(e.nodeId(), e.timestamp());
                }
                case APPROVAL_GRANTED, APPROVAL_REJECTED -> {
                    if (e.type() == EventType.APPROVAL_GRANTED) granted++; else rejected++;
                    Instant opened = approvalOpened.remove(e.nodeId());
                    if (opened != null) {
                        Duration w = Duration.between(opened, e.timestamp());
                        humanWait = humanWait.plus(w);
                        waits.computeIfAbsent(e.nodeId(), k -> new ArrayList<>())
                                .add(new Duration[]{Duration.ofMillis(opened.toEpochMilli()),
                                        Duration.ofMillis(e.timestamp().toEpochMilli())});
                    }
                }
                case NODE_RESET -> firstFailure.remove(e.nodeId());
                case NODE_STATUS_CHANGED -> {
                    String to = e.get("to");
                    statuses.put(e.nodeId(), to);
                    if ("RETRYING".equals(to)) retries++;
                    if ("ROLLED_BACK".equals(to)) rollbacks++;
                    if ("SUCCEEDED".equals(to) && firstFailure.containsKey(e.nodeId())) {
                        Instant t0 = firstFailure.remove(e.nodeId());
                        Duration repair = Duration.between(t0, e.timestamp());
                        repairTimes.add(repair);
                        Duration waited = overlap(waits.getOrDefault(e.nodeId(), List.of()), t0, e.timestamp());
                        repairTimesNoWait.add(repair.minus(waited));
                    }
                }
                case RUN_SUCCEEDED, RUN_FAILED, RUN_ROLLED_BACK, RUN_STOPPED, RUN_PAUSED -> {
                    finalStatus = e.type().name().substring(4);
                    end = e.timestamp();
                }
                case RUN_RESUMED -> finalStatus = "RUNNING";
                default -> { }
            }
        }
        return new RunMetrics(runId, finalStatus, nodes, statuses, attempts, failed, retries, fallbacks, rollbacks,
                compensations, faults, requested, granted, rejected, repairTimes.size(),
                Duration.between(start, end), humanWait, mean(repairTimes), mean(repairTimesNoWait), tokens, cost);
    }

    private static Duration overlap(List<Duration[]> windows, Instant from, Instant to) {
        long total = 0;
        for (Duration[] w : windows) {
            long s = Math.max(w[0].toMillis(), from.toEpochMilli());
            long e = Math.min(w[1].toMillis(), to.toEpochMilli());
            if (e > s) total += e - s;
        }
        return Duration.ofMillis(total);
    }

    private static Optional<Duration> mean(List<Duration> ds) {
        if (ds.isEmpty()) {
            return Optional.empty();
        }
        long total = ds.stream().mapToLong(Duration::toMillis).sum();
        return Optional.of(Duration.ofMillis(total / ds.size()));
    }

    /** Plain values (durations in milliseconds) for JSON reports. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", runId);
        m.put("status", finalStatus);
        m.put("nodes", nodes);
        m.put("nodeStatuses", nodeStatuses);
        m.put("attempts", attempts);
        m.put("failedAttempts", failedAttempts);
        m.put("attemptSuccessRate", attemptSuccessRate());
        m.put("retries", retries);
        m.put("fallbacks", fallbacks);
        m.put("rollbacks", rollbacks);
        m.put("compensations", compensations);
        m.put("faultsInjected", faultsInjected);
        m.put("approvalsRequested", approvalsRequested);
        m.put("approvalsGranted", approvalsGranted);
        m.put("approvalsRejected", approvalsRejected);
        m.put("recoveredNodes", recoveredNodes);
        m.put("endToEndMs", endToEnd.toMillis());
        m.put("humanWaitMs", humanWait.toMillis());
        m.put("mttrMs", mttr.map(Duration::toMillis).orElse(null));
        m.put("mttrExcludingHumanWaitMs", mttrExcludingHumanWait.map(Duration::toMillis).orElse(null));
        m.put("tokens", tokens);
        m.put("costUsd", costUsd);
        return m;
    }

    /** One-screen summary for the CLI and the evidence pack. */
    public String summary() {
        return String.format(
                "run %s: %s | nodes %d | attempts %d (failed %d, success rate %.0f%%) | retries %d | fallbacks %d"
                        + " | rollbacks %d | compensations %d | injected faults %d | approvals %d/%d (rejected %d)"
                        + " | MTTR %s (excl. human wait %s) | end-to-end %s | tokens %d | cost $%.4f",
                runId, finalStatus, nodes, attempts, failedAttempts, attemptSuccessRate() * 100, retries, fallbacks,
                rollbacks, compensations, faultsInjected, approvalsGranted, approvalsRequested, approvalsRejected,
                mttr.map(RunMetrics::fmt).orElse("n/a"), mttrExcludingHumanWait.map(RunMetrics::fmt).orElse("n/a"),
                fmt(endToEnd), tokens, costUsd);
    }

    static String fmt(Duration d) {
        return d.toMillis() < 1000 ? d.toMillis() + "ms" : String.format("%.1fs", d.toMillis() / 1000.0);
    }
}
