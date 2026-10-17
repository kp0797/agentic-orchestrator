package com.agentic.orchestrator.gates;

import com.agentic.orchestrator.artifacts.ArtifactDraft;
import com.agentic.orchestrator.domain.TaskNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What an exit gate may inspect: the node and the outputs of the current attempt. A workspace is
 * present for nodes that change code (Day 2: builds and scanners run against it).
 */
public record GateContext(String runId, TaskNode node, int attempt, List<ArtifactDraft> outputs,
                          Optional<Path> workspace) {
    public GateContext {
        outputs = List.copyOf(outputs);
        workspace = workspace == null ? Optional.empty() : workspace;
    }
}
