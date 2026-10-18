package com.agentic.orchestrator.eval;

import com.agentic.orchestrator.events.Event;
import com.agentic.orchestrator.events.EventType;
import com.agentic.orchestrator.metrics.RunMetrics;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One complete scenario run (planning, any superseded executions and the final execution), summed
 * across its engine runs. Computed from the audit logs, like every other metric.
 */
public record ScenarioRunResult(String scenario, int index, String status, String reason, List<String> runIds,
                                int replans, int attempts, int failedAttempts, int retries, int fallbacks,
                                int compensations, int faultsInjected, int recoveredNodes, int humanDecisions,
                                int clarifications, Duration wallTime, Optional<Duration> mttr, long tokens,
                                double costUsd, Path evidence) {

    public boolean succeeded() {
        return "SUCCEEDED".equals(status);
    }

    public static ScenarioRunResult of(String scenario, int index, String status, String reason,
                                       List<List<Event>> logs, int replans, Path evidence) {
        int attempts = 0, failed = 0, retries = 0, fallbacks = 0, compensations = 0, faults = 0, recovered = 0;
        int decisions = 0, clarifications = 0;
        long tokens = 0;
        double cost = 0;
        Instant first = null;
        Instant last = null;
        long mttrTotal = 0;
        int mttrCount = 0;
        for (List<Event> log : logs) {
            if (log.isEmpty()) {
                continue;
            }
            RunMetrics m = RunMetrics.from(log);
            attempts += m.attempts();
            failed += m.failedAttempts();
            retries += m.retries();
            fallbacks += m.fallbacks();
            compensations += m.compensations();
            faults += m.faultsInjected();
            recovered += m.recoveredNodes();
            decisions += m.approvalsGranted() + m.approvalsRejected();
            clarifications += (int) log.stream().filter(e -> e.type() == EventType.CLARIFICATION_RECORDED).count();
            tokens += m.tokens();
            cost += m.costUsd();
            if (m.mttr().isPresent()) {
                mttrTotal += m.mttr().get().toMillis();
                mttrCount++;
            }
            Instant start = log.get(0).timestamp();
            Instant end = log.get(log.size() - 1).timestamp();
            first = first == null || start.isBefore(first) ? start : first;
            last = last == null || end.isAfter(last) ? end : last;
        }
        List<String> ids = logs.stream().filter(l -> !l.isEmpty()).map(l -> l.get(0).runId()).toList();
        return new ScenarioRunResult(scenario, index, status, reason, ids, replans, attempts, failed, retries,
                fallbacks, compensations, faults, recovered, decisions, clarifications,
                first == null ? Duration.ZERO : Duration.between(first, last),
                mttrCount == 0 ? Optional.empty() : Optional.of(Duration.ofMillis(mttrTotal / mttrCount)),
                tokens, cost, evidence);
    }
}
