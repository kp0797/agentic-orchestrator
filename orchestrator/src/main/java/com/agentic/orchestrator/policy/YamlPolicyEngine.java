package com.agentic.orchestrator.policy;

import com.agentic.orchestrator.domain.RiskLevel;
import com.agentic.orchestrator.domain.TaskNode;

/**
 * Pre-run policy from the YAML rules: risk floors by node type (policy may raise the planner's risk,
 * never lower it), denied executors, and the risk at which a human must approve. Rules about the
 * change itself (paths, dependencies, diff size, disabled tests) run as exit gates.
 */
public final class YamlPolicyEngine implements PolicyEngine {
    private final PolicyConfig config;

    public YamlPolicyEngine(PolicyConfig config) {
        this.config = config;
    }

    public PolicyConfig config() {
        return config;
    }

    @Override
    public PolicyDecision evaluate(TaskNode node) {
        RiskLevel risk = node.risk().max(config.riskFloors().getOrDefault(node.type(), RiskLevel.LOW));
        for (String executor : node.executorChain()) {
            if (config.deniedExecutors().contains(executor)) {
                return PolicyDecision.deny(risk, true, "executor '" + executor + "' is denied by policy.yaml");
            }
        }
        boolean approval = node.requiresApproval() || risk.ordinal() >= config.approvalAtOrAbove().ordinal();
        String reason = (approval ? "approval required" : "allowed") + " (risk " + risk
                + (risk != node.risk() ? ", raised from " + node.risk() + " by policy" : "")
                + (node.requiresApproval() ? ", plan requested approval" : "") + ")";
        return PolicyDecision.allow(risk, approval, reason);
    }
}
