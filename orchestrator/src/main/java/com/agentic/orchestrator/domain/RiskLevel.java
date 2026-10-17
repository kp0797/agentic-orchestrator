package com.agentic.orchestrator.domain;

/** Risk of a node's action. Policy, not the planner, has the final say (it may only raise it). */
public enum RiskLevel {
    LOW, MEDIUM, HIGH, CRITICAL;

    public RiskLevel max(RiskLevel other) {
        return this.ordinal() >= other.ordinal() ? this : other;
    }
}
