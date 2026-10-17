package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.artifacts.ArtifactDraft;
import com.agentic.orchestrator.engine.ExecutorFailure;
import com.agentic.orchestrator.engine.NodeContext;
import com.agentic.orchestrator.engine.NodeExecutor;
import com.agentic.orchestrator.engine.NodeResult;
import com.agentic.orchestrator.planning.PlanningJson;
import com.agentic.orchestrator.planning.RequirementSpec;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Raw requirement in, normalized spec out. Read-only: it changes no files. */
public final class RequirementsAgent implements NodeExecutor {
    public static final String KEY = "requirements";
    private final AgentSupport support;

    public RequirementsAgent(AgentSupport support) {
        this.support = support;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        RunContext run = support.contexts().get(ctx.runId());
        AgentSupport.Call c = support.call(KEY, "requirements", Map.of(
                "requirement", run.requirement(),
                "feedback", AgentSupport.feedback(ctx)));
        RequirementSpec spec;
        try {
            spec = PlanningJson.parse(c.response().text(), RequirementSpec.class);
        } catch (IllegalArgumentException e) {
            throw new ExecutorFailure(e.getMessage(), c.response().totalTokens(), c.costUsd(), e);
        }
        String json = PlanningJson.write(spec);
        return new NodeResult(List.of(new ArtifactDraft("requirement-spec.json", "application/json",
                json.getBytes(StandardCharsets.UTF_8), c.model(), c.promptHash())),
                c.response().totalTokens(), c.costUsd(), List.of(),
                spec.acceptanceCriteria().size() + " acceptance criteria, " + spec.ambiguities().size() + " ambiguities");
    }
}
