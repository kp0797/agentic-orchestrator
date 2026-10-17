package com.agentic.orchestrator.domain;

import java.util.List;

/** ADR-lite record an agent must write for any non-trivial choice. Stored with lineage. */
public record DecisionRecord(String title, String context, List<String> options, String choice, String rationale) {
    public DecisionRecord {
        options = List.copyOf(options == null ? List.of() : options);
    }
}
