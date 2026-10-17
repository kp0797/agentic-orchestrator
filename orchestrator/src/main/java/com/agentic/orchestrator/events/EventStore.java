package com.agentic.orchestrator.events;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Append-only, per-run hash-chained event log. This is the single source of truth for run state:
 * the engine rebuilds a run from its events to resume after a stop or a crash.
 */
public interface EventStore {
    Event append(String runId, EventType type, String nodeId, Map<String, String> data, Instant timestamp);

    List<Event> events(String runId);

    List<String> runIds();
}
