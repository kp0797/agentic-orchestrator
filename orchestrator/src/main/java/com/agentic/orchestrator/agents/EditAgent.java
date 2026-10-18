package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.artifacts.Artifact;
import com.agentic.orchestrator.artifacts.ArtifactDraft;
import com.agentic.orchestrator.domain.DecisionRecord;
import com.agentic.orchestrator.engine.CompensationContext;
import com.agentic.orchestrator.engine.ExecutorFailure;
import com.agentic.orchestrator.engine.NodeContext;
import com.agentic.orchestrator.engine.NodeExecutor;
import com.agentic.orchestrator.engine.NodeResult;
import com.agentic.orchestrator.gates.WorkspaceRef;
import com.agentic.orchestrator.planning.PlanningJson;
import com.agentic.orchestrator.policy.PolicyConfig;
import com.agentic.orchestrator.workspace.EditApplier;
import com.agentic.orchestrator.workspace.FileEdit;
import com.agentic.orchestrator.workspace.PathPermissions;
import com.agentic.orchestrator.workspace.RunWorkspace;
import com.agentic.orchestrator.workspace.WorkspaceManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An agent that changes code: architect, coder, tester, docs. It works in its own worktree, starting
 * from its dependencies' commits; returns structured edits; the tool layer applies them inside the
 * agent's writable paths and commits a real diff for the gates and the approver.
 */
public final class EditAgent implements NodeExecutor {

    /** @param includeGlobs files whose contents the agent sees, highest priority first */
    public record Role(String key, String name, String rules, List<String> includeGlobs) {}

    record EditResponse(String summary, List<FileEdit> edits, List<DecisionRecord> decisions) {}

    static final int MAX_CONTEXT_CHARS = 120_000; // roughly 30k tokens

    private final Role role;
    private final AgentSupport support;
    private final WorkspaceManager workspaces;
    private final PolicyConfig policy;

    public EditAgent(Role role, AgentSupport support, WorkspaceManager workspaces, PolicyConfig policy) {
        this.role = role;
        this.support = support;
        this.workspaces = workspaces;
        this.policy = policy;
    }

    @Override
    public String key() {
        return role.key();
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        PolicyConfig.AgentAutonomy autonomy = policy.agent(role.key());
        if (!autonomy.level().equals("EDIT")) {
            throw new IllegalStateException("agent '" + role.key() + "' is " + autonomy.level() + " and cannot edit");
        }
        RunWorkspace ws = workspaces.get(ctx.runId());
        RunWorkspace.Worktree wt = ws.prepare(ctx.node().id(), baseCommits(ctx));
        workspaces.remember(ctx.runId(), wt);
        PathPermissions perms = new PathPermissions(autonomy.writable(), policy.protectedPaths());
        RunContext run = support.contexts().get(ctx.runId());
        ContextBundle bundle = ContextBundle.build(wt.directory(), ws.listFiles(wt.directory()), role.includeGlobs(),
                run.impactedFiles(), MAX_CONTEXT_CHARS);

        AgentSupport.Call c = support.call(role.key(), "edit-agent", Map.ofEntries(
                Map.entry("role", role.name()),
                Map.entry("roleRules", role.rules()),
                Map.entry("writable", String.join(", ", autonomy.writable())),
                Map.entry("nodeId", ctx.node().id()),
                Map.entry("nodeType", ctx.node().type().name()),
                Map.entry("description", ctx.node().description()),
                Map.entry("criteria", String.join(", ", ctx.node().acceptanceCriteria())),
                Map.entry("spec", run.spec().map(PlanningJson::write).orElse(run.requirement())),
                Map.entry("codebase", run.codebase().isBlank() ? "(no analysis)" : run.codebase()),
                Map.entry("fileList", bundle.fileList()),
                Map.entry("files", bundle.contents()),
                Map.entry("maxDiffLines", String.valueOf(policy.maxDiffLines())),
                Map.entry("feedback", AgentSupport.feedback(ctx))));
        long tokens = c.response().totalTokens();
        EditResponse response;
        try {
            response = PlanningJson.parse(c.response().text(), EditResponse.class);
            if (response.edits() == null) {
                throw new IllegalArgumentException("response has no edits");
            }
            EditApplier.apply(wt.directory(), response.edits(), perms);
        } catch (IllegalArgumentException | EditApplier.EditRejectedException e) {
            throw new ExecutorFailure(e.getMessage(), tokens, c.costUsd(), e);
        }
        String summary = response.summary() == null ? "" : response.summary();
        WorkspaceRef ref = ws.commit(wt, ctx.node().id() + " (attempt " + ctx.attempt() + "): " + summary);

        List<ArtifactDraft> outputs = new ArrayList<>();
        outputs.add(new ArtifactDraft("workspace.json", "application/json", PlanningJson.write(Map.of(
                "branch", ref.branch(), "base", ref.baseCommit(), "commit", ref.headCommit(),
                "changedFiles", ref.changedFiles())).getBytes(StandardCharsets.UTF_8), c.model(), c.promptHash()));
        outputs.add(new ArtifactDraft("change.md", "text/markdown", changeMarkdown(ctx, summary, ref, response)
                .getBytes(StandardCharsets.UTF_8), c.model(), c.promptHash()));
        outputs.add(new ArtifactDraft("change.diff", "text/x-diff", ref.diff().getBytes(StandardCharsets.UTF_8),
                c.model(), c.promptHash()));
        outputs.add(new ArtifactDraft("context-manifest.json", "application/json",
                bundle.manifestJson().getBytes(StandardCharsets.UTF_8), c.model(), c.promptHash()));
        List<DecisionRecord> decisions = response.decisions() == null ? List.of() : response.decisions();
        return new NodeResult(outputs, tokens, c.costUsd(), decisions, summary).withWorkspace(ref);
    }

    @Override
    public String compensate(CompensationContext ctx) {
        RunWorkspace.Worktree wt = workspaces.worktree(ctx.runId(), ctx.node().id());
        workspaces.get(ctx.runId()).resetToBase(wt);
        return "reset branch " + wt.branch() + " to " + wt.baseCommit().substring(0, 8);
    }

    /** Commits produced by the dependencies; this node starts from their merge. */
    static List<String> baseCommits(NodeContext ctx) {
        List<String> commits = new ArrayList<>();
        for (String dep : ctx.node().dependsOn()) {
            for (Artifact a : ctx.inputsFrom(dep)) {
                if (a.name().equals("workspace.json")) {
                    commits.add(PlanningJson.parse(a.contentAsString(), Map.class).get("commit").toString());
                }
            }
        }
        return commits.stream().distinct().toList();
    }

    private static String changeMarkdown(NodeContext ctx, String summary, WorkspaceRef ref, EditResponse r) {
        StringBuilder sb = new StringBuilder("# " + ctx.node().id() + " (attempt " + ctx.attempt() + ")\n\n")
                .append(summary).append("\n\n## Files (+").append(ref.addedLines()).append(" / -")
                .append(ref.removedLines()).append(")\n");
        ref.changedFiles().forEach(f -> sb.append("- ").append(f).append('\n'));
        if (r.decisions() != null && !r.decisions().isEmpty()) {
            sb.append("\n## Decisions\n");
            r.decisions().forEach(d -> sb.append("- **").append(d.title()).append("**: ").append(d.choice())
                    .append(" (").append(d.rationale()).append(")\n"));
        }
        return sb.toString();
    }

    /** The four editing roles, with what each may see. What each may write lives in autonomy.yaml. */
    public static List<Role> standardRoles() {
        return List.of(
                new Role("architect", "architect",
                        "Produce the design as files: docs/design.md (components, data model, key decisions), the "
                                + "OpenAPI contract at src/main/resources/openapi/api.yaml, and Java interfaces, records "
                                + "and exceptions in packages ending in .api and .domain. Contracts only, with one "
                                + "exception: you also create every shared file the parallel implementation tasks "
                                + "would otherwise collide on (application.yml settings, the global exception "
                                + "handler and error body under a .common package, configuration classes under a "
                                + ".config package, and all pom.xml dependency changes); implementers cannot edit "
                                + "those. In docs/design.md, assign each implementation task the package it owns, and "
                                + "name every implementation class the coder "
                                + "must create (fully qualified) and how to construct or inject it: tests are written "
                                + "in parallel from your design and can only use the names you give. Everything you "
                                + "write must compile. Keep it compact: signatures and records with one-line Javadoc, no "
                                + "example code, and docs/design.md under 150 lines.",
                        List.of("pom.xml", "src/main/resources/application.yml", "docs/**", "src/main/java/**",
                        "src/main/resources/**")),
                new Role("coder", "coder",
                        "Implement the design under src/main/java, only in the package docs/design.md assigns "
                                + "to your task (plus Flyway migrations if your task is a schema migration). Parallel "
                                + "tasks are merged later, so never create or edit files outside your package. "
                                + "Do not change the interfaces' signatures; if the "
                                + "design is wrong, make the smallest compatible change and record a decision. Add "
                                + "Flyway migrations under src/main/resources/db/migration when the data model needs "
                                + "them. Do not write tests.",
                        List.of("docs/**", "src/main/resources/openapi/**", "src/main/java/**", "src/main/resources/**",
                                "pom.xml")),
                new Role("tester", "tester",
                        "Write JUnit 5 + AssertJ tests under src/test/java for the acceptance criteria, against "
                                + "the interfaces and the API contract. You cannot see the implementation, and that is "
                                + "deliberate: use the implementation class names given in docs/design.md. Name each "
                                + "test after the criterion it checks (for example ac1_...). "
                                + "Tag tests that need Docker with @Tag(\"docker\"). Criteria about throughput or "
                                + "latency under load cannot be proven by unit tests: cover them with a k6 script in "
                                + "src/test/k6/ and say so in its header comment; never write a unit test that "
                                + "pretends to measure them.",
                        List.of("docs/**", "src/main/resources/openapi/**", "src/main/java/**/api/**",
                                "src/main/java/**/domain/**", "src/test/**", "pom.xml")),
                new Role("docs", "documentation",
                        "Write or update README.md and docs/** so a new engineer can run and use the service: how "
                                + "to run it, API examples with curl, configuration. Describe only what exists.",
                        List.of("README.md", "docs/**", "src/main/resources/openapi/**", "src/main/java/**/api/**",
                                "src/main/resources/application.yml")));
    }
}
