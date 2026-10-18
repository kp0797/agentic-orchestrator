package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.artifacts.ArtifactDraft;
import com.agentic.orchestrator.codebase.ImpactAnalyzer;
import com.agentic.orchestrator.codebase.ImpactReport;
import com.agentic.orchestrator.codebase.RepoIndex;
import com.agentic.orchestrator.engine.NodeContext;
import com.agentic.orchestrator.engine.NodeExecutor;
import com.agentic.orchestrator.engine.NodeResult;
import com.agentic.orchestrator.planning.PlanningJson;
import com.agentic.orchestrator.planning.RequirementSpec;
import com.agentic.orchestrator.workspace.WorkspaceManager;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Deterministic codebase analysis between the spec and the plan: indexes the run's starting code
 * and reports what the requirement is likely to touch. No model is involved; the report is an
 * artifact, so lineage shows exactly what the planner and agents were told about the code.
 */
public final class CodebaseAnalyzer implements NodeExecutor {
    public static final String KEY = "codebase-analyzer";
    public static final String REPORT = "impact.md";

    private final WorkspaceManager workspaces;
    private final RunContext.Store contexts;

    public CodebaseAnalyzer(WorkspaceManager workspaces, RunContext.Store contexts) {
        this.workspaces = workspaces;
        this.contexts = contexts;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        RunContext run = contexts.get(ctx.runId());
        RequirementSpec spec = PlannerAgent.specFromInputs(ctx, contexts);
        StringBuilder text = new StringBuilder(run.requirement()).append('\n')
                .append(spec.title()).append('\n').append(spec.summary()).append('\n');
        spec.goals().forEach(g -> text.append(g).append('\n'));
        spec.acceptanceCriteria().forEach(c -> text.append(c.given()).append(' ').append(c.when()).append(' ')
                .append(c.then()).append('\n'));

        RepoIndex index = RepoIndex.build(workspaces.get(ctx.runId()).repo());
        ImpactReport report = ImpactAnalyzer.analyze(index, text.toString());
        String markdown = report.markdown();
        run.codebase(markdown, report.impactedFiles());
        String summary = report.emptyProject() ? "new project, nothing to analyze"
                : report.impacted().size() + " types in scope, " + report.tests().size() + " existing tests, next migration V"
                + report.nextMigrationVersion();
        return new NodeResult(List.of(
                new ArtifactDraft(REPORT, "text/markdown", markdown.getBytes(StandardCharsets.UTF_8), null, null),
                new ArtifactDraft("impact.json", "application/json",
                        PlanningJson.write(report).getBytes(StandardCharsets.UTF_8), null, null)),
                0, 0, List.of(), summary);
    }
}
