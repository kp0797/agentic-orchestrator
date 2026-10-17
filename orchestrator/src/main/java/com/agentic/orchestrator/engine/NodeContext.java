package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.artifacts.Artifact;
import com.agentic.orchestrator.domain.TaskNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything an executor gets for one attempt. Inputs come only from the node's declared
 * dependencies (the lineage-built context bundle), never from the whole run.
 *
 * @param feedback why the previous attempt failed (trimmed gate or error output), empty on attempt 1
 */
public record NodeContext(String runId, TaskNode node, int attempt, String executorKey,
                          Map<String, List<Artifact>> inputs, Optional<String> feedback) {
    public NodeContext {
        inputs = Map.copyOf(inputs);
        feedback = feedback == null ? Optional.empty() : feedback;
    }

    public List<Artifact> inputsFrom(String dependencyId) {
        return inputs.getOrDefault(dependencyId, List.of());
    }
}
