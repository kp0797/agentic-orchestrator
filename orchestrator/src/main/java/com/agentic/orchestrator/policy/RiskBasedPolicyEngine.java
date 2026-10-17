package com.agentic.orchestrator.policy;

import com.agentic.orchestrator.domain.NodeType;
import com.agentic.orchestrator.domain.RiskLevel;
import com.agentic.orchestrator.domain.TaskNode;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Day-1 policy: a floor of risk per node type, and approval for anything HIGH or above. Day 2 replaces
 * this with YAML rules (protected paths, dependencies, licences, diff size) behind the same interface.
 */
public final class RiskBasedPolicyEngine implements PolicyEngine {
    private final Map<NodeType, RiskLevel> floors = new EnumMap<>(NodeType.class);
    private final Set<String> deniedExecutors;

    public RiskBasedPolicyEngine() {
        this(Set.of());
    }

    public RiskBasedPolicyEngine(Set<String> deniedExecutors) {
        this.deniedExecutors = Set.copyOf(deniedExecutors);
        floors.put(NodeType.SCHEMA_MIGRATION, RiskLevel.HIGH);
        floors.put(NodeType.RELEASE_READINESS, RiskLevel.HIGH);
        floors.put(NodeType.DESIGN, RiskLevel.MEDIUM);
        floors.put(NodeType.IMPLEMENT, RiskLevel.MEDIUM);
    }

    @Override
    public PolicyDecision evaluate(TaskNode node) {
        RiskLevel risk = node.risk().max(floors.getOrDefault(node.type(), RiskLevel.LOW));
        for (String executor : node.executorChain()) {
            if (deniedExecutors.contains(executor)) {
                return PolicyDecision.deny(risk, true, "executor '" + executor + "' is not permitted by policy");
            }
        }
        boolean approval = node.requiresApproval() || risk.ordinal() >= RiskLevel.HIGH.ordinal();
        String reason = approval
                ? "approval required (risk " + risk + (node.requiresApproval() ? ", requested by plan" : "") + ")"
                : "allowed (risk " + risk + ")";
        return PolicyDecision.allow(risk, approval, reason);
    }
}
