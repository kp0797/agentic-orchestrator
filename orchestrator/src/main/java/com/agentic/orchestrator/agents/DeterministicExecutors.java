package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.artifacts.ArtifactDraft;
import com.agentic.orchestrator.engine.CompensationContext;
import com.agentic.orchestrator.engine.NodeContext;
import com.agentic.orchestrator.engine.NodeExecutor;
import com.agentic.orchestrator.engine.NodeResult;
import com.agentic.orchestrator.gates.SecurityScan;
import com.agentic.orchestrator.gates.WorkspaceRef;
import com.agentic.orchestrator.planning.PlanningJson;
import com.agentic.orchestrator.workspace.RunWorkspace;
import com.agentic.orchestrator.workspace.WorkspaceManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Steps that need no model: merging, scanning and assembling release evidence. */
public final class DeterministicExecutors {
    private DeterministicExecutors() {}

    static ArtifactDraft workspaceJson(WorkspaceRef ref) {
        return new ArtifactDraft("workspace.json", "application/json", PlanningJson.write(Map.of(
                "branch", ref.branch(), "base", ref.baseCommit(), "commit", ref.headCommit(),
                "changedFiles", ref.changedFiles())).getBytes(StandardCharsets.UTF_8), null, null);
    }

    static ArtifactDraft text(String name, String body) {
        return new ArtifactDraft(name, "text/plain", body.getBytes(StandardCharsets.UTF_8), null, null);
    }

    /** JOIN: merges the dependency branches; its gates then run the full build and test suite. */
    public static final class Integrate implements NodeExecutor {
        private final WorkspaceManager workspaces;

        public Integrate(WorkspaceManager workspaces) {
            this.workspaces = workspaces;
        }

        @Override
        public String key() {
            return "integrate";
        }

        @Override
        public NodeResult execute(NodeContext ctx) {
            RunWorkspace ws = workspaces.get(ctx.runId());
            List<String> commits = EditAgent.baseCommits(ctx);
            RunWorkspace.Worktree wt = ws.prepare(ctx.node().id(), commits);
            workspaces.remember(ctx.runId(), wt);
            WorkspaceRef ref = ws.describe(wt);
            String summary = "merged " + commits.size() + " branch(es) at " + ref.headCommit().substring(0, 8);
            return new NodeResult(List.of(workspaceJson(ref), text("integration.txt", summary + "\n"
                    + String.join("\n", commits))), 0, 0, List.of(), summary).withWorkspace(ref);
        }

        @Override
        public String compensate(CompensationContext ctx) {
            return "nothing to undo: integration creates no commits of its own beyond the merge branch";
        }
    }

    /** SECURITY_SCAN: static checks over the integrated tree. The {@code security-clean} gate judges them. */
    public static final class SecurityScanner implements NodeExecutor {
        private final WorkspaceManager workspaces;

        public SecurityScanner(WorkspaceManager workspaces) {
            this.workspaces = workspaces;
        }

        @Override
        public String key() {
            return "security-scan";
        }

        @Override
        public NodeResult execute(NodeContext ctx) {
            RunWorkspace ws = workspaces.get(ctx.runId());
            RunWorkspace.Worktree wt = ws.prepare(ctx.node().id(), EditAgent.baseCommits(ctx));
            workspaces.remember(ctx.runId(), wt);
            List<String> findings = SecurityScan.scan(wt.directory());
            String report = findings.isEmpty() ? "" : String.join("\n", findings) + "\n";
            return new NodeResult(List.of(workspaceJson(ws.describe(wt)), text("security-report.txt",
                    report.isEmpty() ? "\n" : report)), 0, 0, List.of(), findings.size() + " finding(s)");
        }
    }

    /** RELEASE_READINESS: assembles the evidence a human signs off on. Policy makes this node HIGH risk. */
    public static final class ReleaseReadiness implements NodeExecutor {
        private final Function<String, String> runEvidence;

        /** @param runEvidence run id to a Markdown summary of gates, approvals, metrics and audit status */
        public ReleaseReadiness(Function<String, String> runEvidence) {
            this.runEvidence = runEvidence;
        }

        @Override
        public String key() {
            return "release";
        }

        @Override
        public NodeResult execute(NodeContext ctx) {
            StringBuilder sb = new StringBuilder("# Release readiness\n\n");
            sb.append("Inputs:\n");
            ctx.inputs().forEach((dep, arts) -> arts.forEach(a -> sb.append("- ").append(dep).append(": ")
                    .append(a.name()).append(" (").append(a.hash(), 0, 12).append(")\n")));
            sb.append("\n").append(runEvidence.apply(ctx.runId()));
            sb.append("\n\nChecklist for the approver:\n")
                    .append("- [ ] Every acceptance criterion has a passing test\n")
                    .append("- [ ] Migrations are backward compatible\n")
                    .append("- [ ] Security findings reviewed\n")
                    .append("- [ ] Open assumptions and ambiguities accepted\n");
            return NodeResult.of(new ArtifactDraft("release-checklist.md", "text/markdown",
                    sb.toString().getBytes(StandardCharsets.UTF_8), null, null));
        }
    }
}
