package com.agentic.orchestrator.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditVerifierTest {
    private InMemoryEventStore store;
    private final Instant t = Instant.parse("2026-10-17T10:00:00Z");

    @BeforeEach
    void setUp() {
        store = new InMemoryEventStore();
        store.append("r1", EventType.RUN_STARTED, null, Map.of("planId", "p"), t);
        store.append("r1", EventType.NODE_STATUS_CHANGED, "a", Map.of("from", "PENDING", "to", "READY"), t);
        store.append("r1", EventType.APPROVAL_GRANTED, "a", Map.of("approver", "alice", "digest", "d1"), t);
        store.append("r1", EventType.RUN_SUCCEEDED, null, Map.of(), t);
    }

    @Test
    void intactChainVerifies() {
        AuditVerifier.Result r = AuditVerifier.verify(store.events("r1"));
        assertThat(r.valid()).isTrue();
        assertThat(r.checked()).isEqualTo(4);
        assertThat(r.headHash()).isEqualTo(store.events("r1").get(3).hash());
    }

    @Test
    void detectsEditedEventEvenWithRecomputedOwnHash() {
        Event original = store.events("r1").get(2);
        // Attacker changes the approver and recomputes this event's hash; the next event's prevHash no longer matches.
        Event forged = Event.chain("r1", original.seq(), original.timestamp(), original.type(), original.nodeId(),
                Map.of("approver", "mallory", "digest", "d1"), original.prevHash());
        store.tamper("r1", 2, forged);
        AuditVerifier.Result r = AuditVerifier.verify(store.events("r1"));
        assertThat(r.valid()).isFalse();
        assertThat(r.firstBadSeq()).isEqualTo(4);
    }

    @Test
    void detectsEditedEventWithoutRehash() {
        Event original = store.events("r1").get(1);
        Event edited = new Event(original.runId(), original.seq(), original.timestamp(), original.type(),
                original.nodeId(), Map.of("from", "PENDING", "to", "SUCCEEDED"), original.prevHash(), original.hash());
        store.tamper("r1", 1, edited);
        AuditVerifier.Result r = AuditVerifier.verify(store.events("r1"));
        assertThat(r.valid()).isFalse();
        assertThat(r.firstBadSeq()).isEqualTo(2);
        assertThat(r.reason()).contains("does not match its hash");
    }

    @Test
    void detectsDeletedEvent() {
        var events = new java.util.ArrayList<>(store.events("r1"));
        events.remove(1);
        assertThat(AuditVerifier.verify(events).valid()).isFalse();
    }

    @Test
    void chainsAreIndependentPerRun() {
        store.append("r2", EventType.RUN_STARTED, null, Map.of(), t);
        assertThat(store.events("r2").get(0).prevHash()).isEqualTo(Event.GENESIS);
        assertThat(store.events("r2").get(0).seq()).isEqualTo(1);
    }
}
