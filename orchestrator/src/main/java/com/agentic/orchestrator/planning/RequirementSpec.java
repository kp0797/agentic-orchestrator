package com.agentic.orchestrator.planning;

import java.util.List;

/** The normalized requirement: what the requirements agent produces and every later step works from. */
public record RequirementSpec(
        String title,
        String summary,
        List<String> goals,
        List<String> nonFunctional,
        List<AcceptanceCriterion> acceptanceCriteria,
        List<Ambiguity> ambiguities,
        List<String> assumptions,
        List<String> outOfScope) {

    public RequirementSpec {
        goals = nz(goals);
        nonFunctional = nz(nonFunctional);
        acceptanceCriteria = nz(acceptanceCriteria);
        ambiguities = nz(ambiguities);
        assumptions = nz(assumptions);
        outOfScope = nz(outOfScope);
    }

    public record AcceptanceCriterion(String id, String given, String when, String then) {}

    /** @param confidence LOW, MEDIUM or HIGH: how safe the proposed default is without asking */
    public record Ambiguity(String question, String proposedDefault, String confidence) {}

    public List<String> criterionIds() {
        return acceptanceCriteria.stream().map(AcceptanceCriterion::id).toList();
    }

    public List<Ambiguity> lowConfidence() {
        return ambiguities.stream().filter(a -> "LOW".equalsIgnoreCase(a.confidence())).toList();
    }

    private static <T> List<T> nz(List<T> l) {
        return List.copyOf(l == null ? List.of() : l);
    }
}
