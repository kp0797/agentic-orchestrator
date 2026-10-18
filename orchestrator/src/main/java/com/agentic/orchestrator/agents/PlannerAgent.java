package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.artifacts.Artifact;
import com.agentic.orchestrator.artifacts.ArtifactDraft;
import com.agentic.orchestrator.engine.ExecutorFailure;
import com.agentic.orchestrator.engine.NodeContext;
import com.agentic.orchestrator.engine.NodeExecutor;
import com.agentic.orchestrator.engine.NodeResult;
import com.agentic.orchestrator.planning.PlanProposal;
import com.agentic.orchestrator.planning.PlanTemplates;
import com.agentic.orchestrator.planning.PlanningJson;
import com.agentic.orchestrator.planning.RequirementSpec;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Proposes the execution DAG. The {@code plan-valid} gate checks it; failures come back as feedback
 * for a repair attempt. After the repairs, the fallback executor {@link Template} takes over.
 */
public final class PlannerAgent implements NodeExecutor {
    public static final String KEY = "planner";
    private final AgentSupport support;
    private final int maxDiffLines;

    public PlannerAgent(AgentSupport support, int maxDiffLines) {
        this.support = support;
        this.maxDiffLines = maxDiffLines;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        RequirementSpec spec = specFromInputs(ctx, support.contexts());
        AgentSupport.Call c = support.call(KEY, "planner", Map.of(
                "spec", PlanningJson.write(spec),
                "maxDiffLines", String.valueOf(maxDiffLines),
                "feedback", AgentSupport.feedback(ctx)));
        PlanProposal proposal;
        try {
            PlanProposal parsed = PlanningJson.parse(c.response().text(), PlanProposal.class);
            proposal = new PlanProposal(parsed.rationale(), parsed.nodes(), "llm");
        } catch (IllegalArgumentException e) {
            throw new ExecutorFailure(e.getMessage(), c.response().totalTokens(), c.costUsd(), e);
        }
        return new NodeResult(List.of(new ArtifactDraft("plan.json", "application/json",
                PlanningJson.write(proposal).getBytes(StandardCharsets.UTF_8), c.model(), c.promptHash())),
                c.response().totalTokens(), c.costUsd(), List.of(), proposal.nodes().size() + " nodes proposed");
    }

    /** The spec comes from the requirements node's artifact (lineage), and is shared with the plan gate. */
    static RequirementSpec specFromInputs(NodeContext ctx, RunContext.Store contexts) {
        for (List<Artifact> list : ctx.inputs().values()) {
            for (Artifact a : list) {
                if (a.name().equals("requirement-spec.json")) {
                    RequirementSpec spec = PlanningJson.parse(a.contentAsString(), RequirementSpec.class);
                    contexts.get(ctx.runId()).spec(spec);
                    return spec;
                }
            }
        }
        throw new IllegalStateException("planner has no requirement-spec.json input");
    }

    /** Deterministic fallback planner: always valid, and labelled as a fallback in the plan itself. */
    public static final class Template implements NodeExecutor {
        public static final String KEY = "template-planner";
        private final RunContext.Store contexts;

        public Template(RunContext.Store contexts) {
            this.contexts = contexts;
        }

        @Override
        public String key() {
            return KEY;
        }

        @Override
        public NodeResult execute(NodeContext ctx) {
            RequirementSpec spec = specFromInputs(ctx, contexts);
            PlanProposal plan = PlanTemplates.standard(spec, ctx.feedback().orElse("planner unavailable"));
            return NodeResult.of(new ArtifactDraft("plan.json", "application/json",
                    PlanningJson.write(plan).getBytes(StandardCharsets.UTF_8), null, null));
        }
    }
}
