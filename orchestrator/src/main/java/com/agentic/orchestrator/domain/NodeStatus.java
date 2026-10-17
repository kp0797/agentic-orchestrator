package com.agentic.orchestrator.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Node lifecycle. Every status change goes through {@link #canTransitionTo(NodeStatus)}, so an
 * illegal move is a bug that fails loudly rather than a silent state corruption.
 */
public enum NodeStatus {
    PENDING,
    READY,
    RUNNING,
    VERIFYING,
    AWAITING_APPROVAL,
    RETRYING,
    SUCCEEDED,
    FAILED,
    ROLLED_BACK,
    STALE,
    SKIPPED,
    CANCELLED;

    private static final Map<NodeStatus, Set<NodeStatus>> ALLOWED = new EnumMap<>(NodeStatus.class);

    static {
        ALLOWED.put(PENDING, EnumSet.of(READY, SKIPPED, CANCELLED, STALE, FAILED));
        ALLOWED.put(READY, EnumSet.of(RUNNING, CANCELLED, SKIPPED, PENDING));
        ALLOWED.put(RUNNING, EnumSet.of(VERIFYING, RETRYING, FAILED, CANCELLED));
        ALLOWED.put(VERIFYING, EnumSet.of(SUCCEEDED, AWAITING_APPROVAL, RETRYING, FAILED, CANCELLED));
        ALLOWED.put(AWAITING_APPROVAL, EnumSet.of(SUCCEEDED, RETRYING, FAILED, CANCELLED, STALE));
        ALLOWED.put(RETRYING, EnumSet.of(RUNNING, CANCELLED, FAILED));
        ALLOWED.put(SUCCEEDED, EnumSet.of(ROLLED_BACK, STALE));
        ALLOWED.put(FAILED, EnumSet.of(ROLLED_BACK, PENDING));
        ALLOWED.put(ROLLED_BACK, EnumSet.of(PENDING));
        ALLOWED.put(STALE, EnumSet.of(PENDING));
        ALLOWED.put(SKIPPED, EnumSet.of(PENDING));
        ALLOWED.put(CANCELLED, EnumSet.of(PENDING));
    }

    public boolean canTransitionTo(NodeStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public Set<NodeStatus> allowedNext() {
        return EnumSet.copyOf(ALLOWED.get(this));
    }

    /** No further progress happens in this run without an outside action (resume, re-plan, rollback). */
    public boolean isSettled() {
        return this == SUCCEEDED || this == FAILED || this == ROLLED_BACK || this == SKIPPED || this == CANCELLED;
    }

    /** Statuses that make dependents unable to run. */
    public boolean blocksDependents() {
        return this == FAILED || this == ROLLED_BACK || this == SKIPPED || this == CANCELLED;
    }

    /** Work was in flight when the run stopped or crashed. */
    public boolean isInFlight() {
        return this == READY || this == RUNNING || this == VERIFYING || this == RETRYING;
    }
}
