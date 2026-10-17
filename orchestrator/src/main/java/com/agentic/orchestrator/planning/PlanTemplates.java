package com.agentic.orchestrator.planning;

import java.util.List;

/**
 * The deterministic fallback when the planner cannot produce a valid plan after its repairs. It
 * always passes validation, and the evidence pack says the template was used.
 */
public final class PlanTemplates {
    private PlanTemplates() {}

    public static PlanProposal standard(RequirementSpec spec, String why) {
        List<String> all = spec.criterionIds();
        return new PlanProposal("template fallback: " + why, List.of(
                node("design", "DESIGN", "API contract, data model, interfaces and ADRs for: " + spec.title(),
                        List.of(), "MEDIUM", true, all),
                node("implement", "IMPLEMENT", "Implement the design so every criterion holds",
                        List.of("design"), "MEDIUM", false, all),
                node("write-tests", "TEST", "Unit and integration tests derived from the acceptance criteria",
                        List.of("design"), "LOW", false, all),
                node("integrate", "JOIN", "Merge implementation and tests; full build and test suite",
                        List.of("implement", "write-tests"), "MEDIUM", false, List.of()),
                node("docs", "DOCS", "README and API usage notes", List.of("integrate"), "LOW", false, List.of()),
                node("security-scan", "SECURITY_SCAN", "Secret and unsafe-pattern scan of the integrated code",
                        List.of("integrate"), "LOW", false, List.of()),
                node("release-readiness", "RELEASE_READINESS", "Release checklist and evidence summary",
                        List.of("docs", "security-scan"), "HIGH", true, List.of())),
                "template");
    }

    private static PlanProposal.ProposedNode node(String id, String type, String description, List<String> deps,
                                                  String risk, boolean approval, List<String> criteria) {
        return new PlanProposal.ProposedNode(id, type, description, deps, risk, approval, criteria);
    }
}
