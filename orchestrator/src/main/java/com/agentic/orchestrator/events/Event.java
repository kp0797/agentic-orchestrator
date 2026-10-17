package com.agentic.orchestrator.events;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One append-only audit record. {@code hash} covers every field plus {@code prevHash}, so editing,
 * removing or reordering any event breaks the chain from that point on.
 */
public record Event(
        String runId,
        long seq,
        Instant timestamp,
        EventType type,
        String nodeId,
        Map<String, String> data,
        String prevHash,
        String hash) {

    public static final String GENESIS = "GENESIS";

    public Event {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(type, "type");
        nodeId = nodeId == null ? "" : nodeId;
        data = Collections.unmodifiableMap(new TreeMap<>(data == null ? Map.of() : data));
        Objects.requireNonNull(prevHash, "prevHash");
        Objects.requireNonNull(hash, "hash");
    }

    /** Builds the next event in a chain, computing its hash. */
    public static Event chain(String runId, long seq, Instant ts, EventType type, String nodeId,
                              Map<String, String> data, String prevHash) {
        // Microsecond precision so the hash survives a round trip through Postgres timestamps.
        Instant t = ts.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String n = nodeId == null ? "" : nodeId;
        Map<String, String> d = new TreeMap<>(data == null ? Map.of() : data);
        String hash = EventCodec.hash(runId, seq, t, type, n, d, prevHash);
        return new Event(runId, seq, t, type, n, d, prevHash, hash);
    }

    public String get(String key) {
        return data.get(key);
    }

    public boolean hasNode() {
        return !nodeId.isEmpty();
    }
}
