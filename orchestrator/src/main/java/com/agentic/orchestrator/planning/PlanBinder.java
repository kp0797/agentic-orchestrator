package com.agentic.orchestrator.planning;

import com.agentic.orchestrator.domain.NodeType;
import com.agentic.orchestrator.domain.Plan;
import com.agentic.orchestrator.domain.RetryPolicy;
import com.agentic.orchestrator.domain.RiskLevel;
import com.agentic.orchestrator.domain.TaskNode;
import com.agentic.orchestrator.gates.MavenGate;
import com.agentic.orchestrator.gates.NoSecretsGate;
import com.agentic.orchestrator.gates.OutputsPresentGate;
import com.agentic.orchestrator.gates.WorkspaceGates;
import com.agentic.orchestrator.gates.SecurityScan;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a validated proposal into an executable plan. The planner chooses nodes and order; the
 * binder, not the planner, chooses which executor runs each type and which gates it must pass.
 */
public final class PlanBinder {
    private PlanBinder() {}

    public static Plan bind(String planId, PlanProposal proposal, RetryPolicy retry) {
        return bind(planId, proposal, retry, List.of());
    }

    /** @param extraJoinGates scenario-specific checks on the merged code, run after its tests pass */
    public static Plan bind(String planId, PlanProposal proposal, RetryPolicy retry, List<String> extraJoinGates) {
        return bind(planId, 1, proposal, retry, extraJoinGates);
    }

    /** @param version 2 and up after re-planning (a clarification changed the spec) */
    public static Plan bind(String planId, int version, PlanProposal proposal, RetryPolicy retry,
                            List<String> extraJoinGates) {
        List<TaskNode> nodes = new ArrayList<>();
        for (PlanProposal.ProposedNode p : proposal.nodes()) {
            NodeType type = NodeType.valueOf(p.type());
            TaskNode.Builder b = TaskNode.builder(p.id(), type)
                    .description(p.description())
                    .dependsOn(p.dependsOn().toArray(String[]::new))
                    .risk(p.risk() == null ? RiskLevel.LOW : RiskLevel.valueOf(p.risk()))
                    .requiresApproval(p.requiresApproval())
                    .acceptanceCriteria(p.acceptanceCriteria().toArray(String[]::new))
                    .retryPolicy(retry);
            String[] changeGates = {OutputsPresentGate.ID, WorkspaceGates.ProtectedPaths.ID,
                    WorkspaceGates.SecretsInDiff.ID, WorkspaceGates.DiffSize.ID, WorkspaceGates.ApprovalPaths.ID,
                    WorkspaceGates.NewDependency.ID, WorkspaceGates.DisabledTests.ID,
                    WorkspaceGates.MigrationsImmutable.ID};
            switch (type) {
                case DESIGN -> b.executor("architect").exitGates(concat(changeGates, WorkspaceGates.NonEmptyChange.ID,
                        MavenGate.BUILD));
                case IMPLEMENT, SCHEMA_MIGRATION -> b.executor("coder").exitGates(concat(changeGates,
                        WorkspaceGates.NonEmptyChange.ID, MavenGate.BUILD));
                // Tests are written without seeing the implementation, so they can only compile once
                // merged with it. The JOIN gate is where they must compile and pass.
                case TEST -> b.executor("tester").exitGates(concat(changeGates, WorkspaceGates.NonEmptyChange.ID));
                // Deterministic steps: retrying would give the same answer, so they get one attempt.
                case JOIN -> b.executor("integrate").retryPolicy(RetryPolicy.NO_RETRY).exitGates(concat(new String[]{
                        OutputsPresentGate.ID, WorkspaceGates.ProtectedPaths.ID, WorkspaceGates.MigrationsImmutable.ID,
                        MavenGate.BUILD, MavenGate.TESTS}, extraJoinGates.toArray(String[]::new)));
                case DOCS -> b.executor("docs").exitGates(concat(changeGates));
                case SECURITY_SCAN -> b.executor("security-scan").retryPolicy(RetryPolicy.NO_RETRY)
                        .exitGates(OutputsPresentGate.ID, SecurityScan.Gate.ID);
                case RELEASE_READINESS -> b.executor("release").retryPolicy(RetryPolicy.NO_RETRY)
                        .exitGates(OutputsPresentGate.ID, NoSecretsGate.ID);
                default -> throw new IllegalArgumentException("type " + type + " cannot be bound");
            }
            nodes.add(b.build());
        }
        return new Plan(planId, version, nodes);
    }

    private static String[] concat(String[] base, String... more) {
        List<String> all = new ArrayList<>(List.of(base));
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
    }
}
