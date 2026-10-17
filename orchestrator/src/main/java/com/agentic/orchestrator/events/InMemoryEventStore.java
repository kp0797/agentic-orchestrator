package com.agentic.orchestrator.events;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryEventStore implements EventStore {
    private final Map<String, List<Event>> byRun = new LinkedHashMap<>();

    @Override
    public synchronized Event append(String runId, EventType type, String nodeId, Map<String, String> data,
                                     Instant timestamp) {
        List<Event> log = byRun.computeIfAbsent(runId, k -> new ArrayList<>());
        String prev = log.isEmpty() ? Event.GENESIS : log.get(log.size() - 1).hash();
        Event e = Event.chain(runId, log.size() + 1L, timestamp, type, nodeId, data, prev);
        log.add(e);
        return e;
    }

    @Override
    public synchronized List<Event> events(String runId) {
        return List.copyOf(byRun.getOrDefault(runId, List.of()));
    }

    @Override
    public synchronized List<String> runIds() {
        return List.copyOf(byRun.keySet());
    }

    /** Test hook: overwrite an event in place, as an attacker with database access could. */
    synchronized void tamper(String runId, int index, Event replacement) {
        byRun.get(runId).set(index, replacement);
    }
}
