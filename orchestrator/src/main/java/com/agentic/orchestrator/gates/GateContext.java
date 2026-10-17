package com.agentic.orchestrator.gates;

import com.agentic.orchestrator.artifacts.ArtifactDraft;
import com.agentic.orchestrator.domain.TaskNode;

import java.util.List;
import java.util.Optional;

/**
 * What an exit gate may inspect: the node, the outputs of the current attempt and, for nodes that
 * change code, the committed change in their workspace.
 */
public record GateContext(String runId, TaskNode node, int attempt, List<ArtifactDraft> outputs,
                          Optional<WorkspaceRef> workspace) {
    public GateContext {
        outputs = List.copyOf(outputs);
        workspace = workspace == null ? Optional.empty() : workspace;
    }
}
