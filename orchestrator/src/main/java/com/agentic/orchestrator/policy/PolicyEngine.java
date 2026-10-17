package com.agentic.orchestrator.policy;

import com.agentic.orchestrator.domain.TaskNode;

/** Decides, before a node runs, whether it may run and whether a human must approve its output. */
public interface PolicyEngine {
    PolicyDecision evaluate(TaskNode node);
}
