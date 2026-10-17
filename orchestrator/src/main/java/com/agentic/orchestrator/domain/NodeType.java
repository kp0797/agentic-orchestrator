package com.agentic.orchestrator.domain;

/** The kinds of work a task node can represent. The planner may only use these. */
public enum NodeType {
    REQUIREMENTS,
    DESIGN,
    IMPLEMENT,
    TEST,
    DOCS,
    SECURITY_SCAN,
    SCHEMA_MIGRATION,
    RELEASE_READINESS,
    JOIN
}
