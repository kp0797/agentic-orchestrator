package com.agentic.orchestrator.policy;

import com.agentic.orchestrator.domain.NodeType;
import com.agentic.orchestrator.domain.RiskLevel;

import java.util.List;
import java.util.Map;

/**
 * Policy-as-code, loaded from {@code policies/policy.yaml} and {@code policies/autonomy.yaml}.
 * Both files are protected paths: no agent can change the rules it runs under.
 */
public record PolicyConfig(
        Map<NodeType, RiskLevel> riskFloors,
        RiskLevel approvalAtOrAbove,
        List<String> protectedPaths,
        List<String> approvalPaths,
        int maxDiffLines,
        List<String> deniedExecutors,
        List<String> licenseAllowlist,
        Map<String, AgentAutonomy> agents) {

    public PolicyConfig {
        riskFloors = Map.copyOf(riskFloors);
        protectedPaths = List.copyOf(protectedPaths);
        approvalPaths = List.copyOf(approvalPaths);
        deniedExecutors = List.copyOf(deniedExecutors);
        licenseAllowlist = List.copyOf(licenseAllowlist);
        agents = Map.copyOf(agents);
        if (maxDiffLines < 1) {
            throw new IllegalArgumentException("maxDiffLines must be positive");
        }
    }

    /**
     * @param level    READ_ONLY (produces documents only), EDIT (changes files in its writable paths)
     * @param writable globs the agent may create, change or delete
     */
    public record AgentAutonomy(String level, List<String> writable) {
        public AgentAutonomy {
            writable = List.copyOf(writable == null ? List.of() : writable);
            if (!level.equals("READ_ONLY") && !level.equals("EDIT")) {
                throw new IllegalArgumentException("autonomy level must be READ_ONLY or EDIT, was " + level);
            }
            if (level.equals("READ_ONLY") && !writable.isEmpty()) {
                throw new IllegalArgumentException("a READ_ONLY agent cannot have writable paths");
            }
        }
    }

    public AgentAutonomy agent(String key) {
        AgentAutonomy a = agents.get(key);
        if (a == null) {
            // Unknown agents get nothing: least privilege by default.
            return new AgentAutonomy("READ_ONLY", List.of());
        }
        return a;
    }
}
