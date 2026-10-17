package com.agentic.orchestrator.events;

import java.util.List;

/**
 * Recomputes a run's hash chain. Detects edited, removed, inserted or reordered events. It cannot
 * detect a chain that was rebuilt from scratch, which is why the head hash is also anchored outside
 * the database (evidence pack and release tag).
 */
public final class AuditVerifier {
    private AuditVerifier() {}

    public record Result(boolean valid, long checked, long firstBadSeq, String reason, String headHash) {
        static Result ok(long checked, String head) {
            return new Result(true, checked, -1, "chain intact", head);
        }

        static Result broken(long checked, long seq, String reason) {
            return new Result(false, checked, seq, reason, null);
        }
    }

    public static Result verify(List<Event> events) {
        String prev = Event.GENESIS;
        long expectedSeq = 1;
        for (Event e : events) {
            if (e.seq() != expectedSeq) {
                return Result.broken(expectedSeq - 1, e.seq(), "expected seq " + expectedSeq + " but found " + e.seq());
            }
            if (!e.prevHash().equals(prev)) {
                return Result.broken(expectedSeq - 1, e.seq(), "prevHash does not match the previous event");
            }
            String recomputed = EventCodec.hash(e.runId(), e.seq(), e.timestamp(), e.type(), e.nodeId(), e.data(),
                    e.prevHash());
            if (!recomputed.equals(e.hash())) {
                return Result.broken(expectedSeq - 1, e.seq(), "event content does not match its hash");
            }
            prev = e.hash();
            expectedSeq++;
        }
        return Result.ok(events.size(), prev);
    }
}
