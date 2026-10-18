package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.planning.PlanProposal;
import com.agentic.orchestrator.planning.RequirementSpec;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Per-run facts every agent may read: the raw requirement and, once produced, the spec and plan. */
public final class RunContext {
    private final String runId;
    private final String scenario;
    private final String requirement;
    private volatile RequirementSpec spec;
    private volatile PlanProposal plan;
    private volatile String codebase = "";
    private volatile java.util.List<String> impactedFiles = java.util.List.of();

    public RunContext(String runId, String scenario, String requirement) {
        this.runId = runId;
        this.scenario = scenario;
        this.requirement = requirement;
    }

    public String runId() { return runId; }
    public String scenario() { return scenario; }
    public String requirement() { return requirement; }
    public Optional<RequirementSpec> spec() { return Optional.ofNullable(spec); }
    public Optional<PlanProposal> plan() { return Optional.ofNullable(plan); }
    public void spec(RequirementSpec s) { this.spec = s; }
    public void plan(PlanProposal p) { this.plan = p; }

    /** The impact analysis as text for prompts; empty until the analysis node has run. */
    public String codebase() { return codebase; }
    public java.util.List<String> impactedFiles() { return impactedFiles; }

    public void codebase(String report, java.util.List<String> files) {
        this.codebase = report == null ? "" : report;
        this.impactedFiles = java.util.List.copyOf(files);
    }

    /** Registry of contexts by run id. */
    public static final class Store {
        private final Map<String, RunContext> byRun = new ConcurrentHashMap<>();

        public RunContext put(RunContext ctx) {
            byRun.put(ctx.runId(), ctx);
            return ctx;
        }

        public RunContext get(String runId) {
            RunContext c = byRun.get(runId);
            if (c == null) {
                throw new IllegalStateException("no context for run " + runId);
            }
            return c;
        }

        public Optional<RunContext> find(String runId) {
            return Optional.ofNullable(byRun.get(runId));
        }

        /** The execution run inherits the planning run's requirement, spec and plan. */
        public RunContext inherit(String parentRunId, String childRunId) {
            RunContext parent = get(parentRunId);
            RunContext child = new RunContext(childRunId, parent.scenario(), parent.requirement());
            parent.spec().ifPresent(child::spec);
            parent.plan().ifPresent(child::plan);
            child.codebase(parent.codebase(), parent.impactedFiles());
            return put(child);
        }
    }
}
