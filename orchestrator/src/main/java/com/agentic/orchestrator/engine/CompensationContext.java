package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.domain.TaskNode;

import java.util.List;

/** Passed to a compensation: the node being undone and the artifact hashes it produced. */
public record CompensationContext(String runId, TaskNode node, List<String> outputHashes, String reason) {
    public CompensationContext {
        outputHashes = List.copyOf(outputHashes);
    }
}
