package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.domain.NodeStatus;
import com.agentic.orchestrator.domain.RunStatus;

import java.util.Map;

public record RunOutcome(String runId, RunStatus status, String reason, Map<String, NodeStatus> nodes) {
    public RunOutcome {
        nodes = Map.copyOf(nodes);
    }

    public NodeStatus node(String id) {
        return nodes.get(id);
    }
}
