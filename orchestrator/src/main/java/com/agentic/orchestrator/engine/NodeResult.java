package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.artifacts.ArtifactDraft;
import com.agentic.orchestrator.domain.DecisionRecord;

import java.util.List;

/** What an executor returns. The engine, not the executor, decides whether it is accepted. */
public record NodeResult(List<ArtifactDraft> outputs, long tokensUsed, double costUsd,
                         List<DecisionRecord> decisions, String summary) {
    public NodeResult {
        outputs = List.copyOf(outputs);
        decisions = List.copyOf(decisions == null ? List.of() : decisions);
        summary = summary == null ? "" : summary;
    }

    public static NodeResult of(ArtifactDraft... outputs) {
        return new NodeResult(List.of(outputs), 0, 0.0, List.of(), "");
    }

    public NodeResult withUsage(long tokens, double cost) {
        return new NodeResult(outputs, tokens, cost, decisions, summary);
    }

    public NodeResult withDecision(DecisionRecord decision) {
        var all = new java.util.ArrayList<>(decisions);
        all.add(decision);
        return new NodeResult(outputs, tokensUsed, costUsd, all, summary);
    }
}
