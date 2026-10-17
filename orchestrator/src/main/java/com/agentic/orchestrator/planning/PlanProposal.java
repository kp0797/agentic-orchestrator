package com.agentic.orchestrator.planning;

import java.util.List;

/** The planner's proposed DAG, before validation and binding. {@code source} is "llm" or "template". */
public record PlanProposal(String rationale, List<ProposedNode> nodes, String source) {
    public PlanProposal {
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
        source = source == null ? "llm" : source;
    }

    public record ProposedNode(String id, String type, String description, List<String> dependsOn, String risk,
                               boolean requiresApproval, List<String> acceptanceCriteria) {
        public ProposedNode {
            dependsOn = List.copyOf(dependsOn == null ? List.of() : dependsOn);
            acceptanceCriteria = List.copyOf(acceptanceCriteria == null ? List.of() : acceptanceCriteria);
            description = description == null ? "" : description;
        }
    }
}
