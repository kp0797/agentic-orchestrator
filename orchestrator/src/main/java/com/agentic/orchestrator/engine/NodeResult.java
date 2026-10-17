package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.artifacts.ArtifactDraft;
import com.agentic.orchestrator.domain.DecisionRecord;
import com.agentic.orchestrator.gates.WorkspaceRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** What an executor returns. The engine, not the executor, decides whether it is accepted. */
public record NodeResult(List<ArtifactDraft> outputs, long tokensUsed, double costUsd,
                         List<DecisionRecord> decisions, String summary, Optional<WorkspaceRef> workspace) {
    public NodeResult {
        outputs = List.copyOf(outputs);
        decisions = List.copyOf(decisions == null ? List.of() : decisions);
        summary = summary == null ? "" : summary;
        workspace = workspace == null ? Optional.empty() : workspace;
    }

    public NodeResult(List<ArtifactDraft> outputs, long tokensUsed, double costUsd, List<DecisionRecord> decisions,
                      String summary) {
        this(outputs, tokensUsed, costUsd, decisions, summary, Optional.empty());
    }

    public static NodeResult of(ArtifactDraft... outputs) {
        return new NodeResult(List.of(outputs), 0, 0.0, List.of(), "");
    }

    public NodeResult withUsage(long tokens, double cost) {
        return new NodeResult(outputs, tokens, cost, decisions, summary, workspace);
    }

    public NodeResult withDecision(DecisionRecord decision) {
        List<DecisionRecord> all = new ArrayList<>(decisions);
        all.add(decision);
        return new NodeResult(outputs, tokensUsed, costUsd, all, summary, workspace);
    }

    public NodeResult withWorkspace(WorkspaceRef ref) {
        return new NodeResult(outputs, tokensUsed, costUsd, decisions, summary, Optional.of(ref));
    }
}
