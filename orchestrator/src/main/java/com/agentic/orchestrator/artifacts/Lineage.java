package com.agentic.orchestrator.artifacts;

import java.util.List;

/**
 * Where an artifact came from: which node and attempt produced it, from which inputs, with which
 * executor, model and prompt. This is what lets a changed input mark downstream work stale.
 */
public record Lineage(
        String runId,
        String producerNodeId,
        int attempt,
        String executorKey,
        List<String> inputHashes,
        String model,
        String promptHash) {

    public Lineage {
        inputHashes = List.copyOf(inputHashes == null ? List.of() : inputHashes);
        model = model == null ? "" : model;
        promptHash = promptHash == null ? "" : promptHash;
    }
}
