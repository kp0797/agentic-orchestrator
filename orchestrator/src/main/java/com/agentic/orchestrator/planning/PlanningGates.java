package com.agentic.orchestrator.planning;

import com.agentic.orchestrator.artifacts.ArtifactDraft;
import com.agentic.orchestrator.gates.Gate;
import com.agentic.orchestrator.gates.GateContext;
import com.agentic.orchestrator.gates.GateResult;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Gates for the planning run. Their failure messages go back to the agent as repair instructions. */
public final class PlanningGates {
    private PlanningGates() {}

    static Optional<ArtifactDraft> named(GateContext ctx, String name) {
        return ctx.outputs().stream().filter(o -> o.name().equals(name)).findFirst();
    }

    /** The spec parses and is complete; low-confidence ambiguities escalate to a human. */
    public static final class SpecValid implements Gate {
        public static final String ID = "spec-valid";

        public String id() { return ID; }

        public GateResult evaluate(GateContext ctx) {
            Optional<ArtifactDraft> out = named(ctx, "requirement-spec.json");
            if (out.isEmpty()) return GateResult.fail(ID, "requirement-spec.json missing");
            RequirementSpec spec;
            try {
                spec = PlanningJson.parse(out.get().contentAsString(), RequirementSpec.class);
            } catch (IllegalArgumentException e) {
                return GateResult.fail(ID, e.getMessage());
            }
            List<String> errors = SpecValidator.validate(spec);
            if (!errors.isEmpty()) return GateResult.fail(ID, String.join("; ", errors));
            List<RequirementSpec.Ambiguity> low = spec.lowConfidence();
            if (!low.isEmpty()) {
                return GateResult.escalate(ID, spec.acceptanceCriteria().size() + " criteria, "
                                + low.size() + " low-confidence ambiguities",
                        "needs clarification: " + low.stream().map(RequirementSpec.Ambiguity::question).toList());
            }
            return GateResult.pass(ID, spec.acceptanceCriteria().size() + " criteria, "
                    + spec.ambiguities().size() + " ambiguities with safe defaults");
        }
    }

    /** The plan passes every structural and coverage rule against the spec. */
    public static final class PlanValid implements Gate {
        public static final String ID = "plan-valid";
        private final Function<String, Optional<RequirementSpec>> specForRun;

        public PlanValid(Function<String, Optional<RequirementSpec>> specForRun) {
            this.specForRun = specForRun;
        }

        public String id() { return ID; }

        public GateResult evaluate(GateContext ctx) {
            Optional<ArtifactDraft> out = named(ctx, "plan.json");
            if (out.isEmpty()) return GateResult.fail(ID, "plan.json missing");
            Optional<RequirementSpec> spec = specForRun.apply(ctx.runId());
            if (spec.isEmpty()) return GateResult.fail(ID, "no requirement spec for this run");
            PlanProposal proposal;
            try {
                proposal = PlanningJson.parse(out.get().contentAsString(), PlanProposal.class);
            } catch (IllegalArgumentException e) {
                return GateResult.fail(ID, e.getMessage());
            }
            List<String> errors = PlanValidator.validate(proposal, spec.get());
            if (!errors.isEmpty()) return GateResult.fail(ID, String.join("; ", errors));
            return GateResult.pass(ID, proposal.nodes().size() + " nodes, every criterion implemented and tested"
                    + ("template".equals(proposal.source()) ? " (template fallback)" : ""));
        }
    }
}
