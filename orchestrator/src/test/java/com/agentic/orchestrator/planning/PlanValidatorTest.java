package com.agentic.orchestrator.planning;

import com.agentic.orchestrator.domain.Plan;
import com.agentic.orchestrator.domain.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanValidatorTest {
    private final RequirementSpec spec = new RequirementSpec("t", "s", List.of(), List.of(), List.of(
            new RequirementSpec.AcceptanceCriterion("AC1", "g", "w", "t"),
            new RequirementSpec.AcceptanceCriterion("AC2", "g", "w", "t")), List.of(), List.of(), List.of());

    @Test
    void templatePlanIsAlwaysValidAndBindsToGatedExecutors() {
        PlanProposal template = PlanTemplates.standard(spec, "test");
        assertThat(PlanValidator.validate(template, spec)).isEmpty();
        Plan plan = PlanBinder.bind("p", template, RetryPolicy.DEFAULT);
        assertThat(plan.node("implement").executorKey()).isEqualTo("coder");
        assertThat(plan.node("integrate").exitGates()).contains("build", "tests");
        assertThat(plan.node("write-tests").exitGates()).doesNotContain("build"); // blind tests compile at the join
        assertThat(plan.node("integrate").retryPolicy().maxAttempts()).isEqualTo(1);
    }

    @Test
    void testsThatDependOnTheCodeAreRejected() {
        List<PlanProposal.ProposedNode> nodes = new ArrayList<>(PlanTemplates.standard(spec, "x").nodes());
        nodes.set(2, new PlanProposal.ProposedNode("write-tests", "TEST", "", List.of("implement"), "LOW", false,
                List.of("AC1", "AC2")));
        assertThat(PlanValidator.validate(new PlanProposal("", nodes, "llm"), spec))
                .anyMatch(e -> e.contains("tests must be written from the criteria"));
    }

    @Test
    void uncoveredCriteriaMissingJoinAndMisplacedReleaseAreRejected() {
        PlanProposal bad = new PlanProposal("", List.of(
                new PlanProposal.ProposedNode("design", "DESIGN", "", List.of(), "LOW", false, List.of("AC1")),
                new PlanProposal.ProposedNode("impl", "IMPLEMENT", "", List.of("design"), "LOW", false, List.of("AC1")),
                new PlanProposal.ProposedNode("tests", "TEST", "", List.of("design"), "LOW", false, List.of("AC1")),
                new PlanProposal.ProposedNode("release", "RELEASE_READINESS", "", List.of("impl"), "HIGH", true, List.of()),
                new PlanProposal.ProposedNode("docs", "DOCS", "", List.of("release"), "LOW", false, List.of())), "llm");
        assertThat(PlanValidator.validate(bad, spec))
                .anyMatch(e -> e.contains("AC2 is not covered by any IMPLEMENT"))
                .anyMatch(e -> e.contains("AC2 is not covered by any TEST"))
                .anyMatch(e -> e.contains("needs a JOIN"))
                .anyMatch(e -> e.contains("RELEASE_READINESS must be the last node"));
    }

    @Test
    void unknownTypesCriteriaAndCyclesAreRejected() {
        PlanProposal bad = new PlanProposal("", List.of(
                new PlanProposal.ProposedNode("deploy", "DEPLOY_TO_PROD", "", List.of(), "LOW", false, List.of("AC9"))), "llm");
        assertThat(PlanValidator.validate(bad, spec)).anyMatch(e -> e.contains("DEPLOY_TO_PROD"))
                .anyMatch(e -> e.contains("unknown criterion AC9"));
        PlanProposal cyclic = new PlanProposal("", List.of(
                new PlanProposal.ProposedNode("a", "DESIGN", "", List.of("b"), "LOW", false, List.of()),
                new PlanProposal.ProposedNode("b", "IMPLEMENT", "", List.of("a"), "LOW", false, List.of())), "llm");
        assertThat(PlanValidator.validate(cyclic, spec)).anyMatch(e -> e.contains("cycle"));
    }

    @Test
    void specValidationAndJsonExtraction() {
        String wrapped = "Sure! ```json\n{\"title\": \"x\", \"acceptanceCriteria\": [{\"id\": \"criterion-1\", "
                + "\"given\": \"g\", \"when\": \"\", \"then\": \"t\"}], \"ambiguities\": [{\"question\": \"q\", "
                + "\"confidence\": \"maybe\"}]}\n```";
        RequirementSpec parsed = PlanningJson.parse(wrapped, RequirementSpec.class);
        assertThat(SpecValidator.validate(parsed))
                .anyMatch(e -> e.contains("must look like AC1"))
                .anyMatch(e -> e.contains("needs given, when and then"))
                .anyMatch(e -> e.contains("confidence LOW, MEDIUM or HIGH"));
    }
}
