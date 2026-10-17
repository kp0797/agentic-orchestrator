package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.agents.AgentSupport;
import com.agentic.orchestrator.agents.DeterministicExecutors;
import com.agentic.orchestrator.agents.EditAgent;
import com.agentic.orchestrator.agents.PlannerAgent;
import com.agentic.orchestrator.agents.PromptLibrary;
import com.agentic.orchestrator.agents.RequirementsAgent;
import com.agentic.orchestrator.agents.RunContext;
import com.agentic.orchestrator.artifacts.Artifact;
import com.agentic.orchestrator.artifacts.ArtifactStore;
import com.agentic.orchestrator.domain.NodeType;
import com.agentic.orchestrator.domain.Plan;
import com.agentic.orchestrator.domain.RetryPolicy;
import com.agentic.orchestrator.domain.RunStatus;
import com.agentic.orchestrator.domain.TaskNode;
import com.agentic.orchestrator.engine.ApprovalHandler;
import com.agentic.orchestrator.engine.Engine;
import com.agentic.orchestrator.engine.EngineOptions;
import com.agentic.orchestrator.engine.ExecutorRegistry;
import com.agentic.orchestrator.engine.FaultInjector;
import com.agentic.orchestrator.engine.PlanStore;
import com.agentic.orchestrator.engine.RunOutcome;
import com.agentic.orchestrator.engine.Sleeper;
import com.agentic.orchestrator.events.AuditVerifier;
import com.agentic.orchestrator.events.Event;
import com.agentic.orchestrator.events.EventStore;
import com.agentic.orchestrator.events.EventType;
import com.agentic.orchestrator.gates.GateRegistry;
import com.agentic.orchestrator.gates.MavenGate;
import com.agentic.orchestrator.gates.NoSecretsGate;
import com.agentic.orchestrator.gates.OutputsPresentGate;
import com.agentic.orchestrator.gates.SecurityScan;
import com.agentic.orchestrator.gates.WorkspaceGates;
import com.agentic.orchestrator.metrics.RunMetrics;
import com.agentic.orchestrator.model.ModelGateway;
import com.agentic.orchestrator.model.ModelSettings;
import com.agentic.orchestrator.planning.PlanBinder;
import com.agentic.orchestrator.planning.PlanProposal;
import com.agentic.orchestrator.planning.PlanningGates;
import com.agentic.orchestrator.planning.PlanningJson;
import com.agentic.orchestrator.policy.PolicyConfig;
import com.agentic.orchestrator.policy.YamlPolicyEngine;
import com.agentic.orchestrator.workspace.WorkspaceManager;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Runs one scenario in two linked runs that share a workspace and an audit trail:
 * <ol>
 *   <li><b>planning</b>: requirements, then a proposed plan that must pass validation and a human;</li>
 *   <li><b>execution</b>: the approved plan, bound to executors and gates.</li>
 * </ol>
 * The second run starts only after the first succeeds, and records which plan artifact it executes.
 */
public final class Orchestrator {
    public record Settings(Path runsDir, PolicyConfig policy, ModelSettings models, MavenGate.Settings maven,
                           EngineOptions engineOptions, Sleeper sleeper) {}

    private final Scenario scenario;
    private final Engine engine;
    private final EventStore events;
    private final ArtifactStore artifacts;
    private final PlanStore plans;
    private final RunContext.Store contexts = new RunContext.Store();
    private final WorkspaceManager workspaces;
    private volatile String planningRunId;
    private volatile String executionRunId;

    public Orchestrator(Scenario scenario, Settings settings, ModelGateway gateway, EventStore events,
                        ArtifactStore artifacts, PlanStore plans, ApprovalHandler approvals) {
        this.scenario = scenario;
        this.events = events;
        this.artifacts = artifacts;
        this.plans = plans;
        this.workspaces = new WorkspaceManager(settings.runsDir());
        PolicyConfig policy = settings.policy();
        AgentSupport support = new AgentSupport(gateway, settings.models(), new PromptLibrary(), contexts);

        ExecutorRegistry executors = new ExecutorRegistry()
                .register(new RequirementsAgent(support))
                .register(new PlannerAgent(support))
                .register(new PlannerAgent.Template(contexts))
                .register(new DeterministicExecutors.Integrate(workspaces))
                .register(new DeterministicExecutors.SecurityScanner(workspaces))
                .register(new DeterministicExecutors.ReleaseReadiness(this::evidence));
        EditAgent.standardRoles().forEach(r -> executors.register(new EditAgent(r, support, workspaces, policy)));

        GateRegistry gates = GateRegistry.withBuiltIns()
                .register(new PlanningGates.SpecValid())
                .register(new PlanningGates.PlanValid(runId -> contexts.find(runId).flatMap(RunContext::spec)))
                .register(new WorkspaceGates.ProtectedPaths(policy.protectedPaths()))
                .register(new WorkspaceGates.DiffSize(policy.maxDiffLines()))
                .register(new WorkspaceGates.NonEmptyChange())
                .register(new WorkspaceGates.ApprovalPaths(policy.approvalPaths()))
                .register(new WorkspaceGates.NewDependency(policy.licenseAllowlist()))
                .register(new WorkspaceGates.DisabledTests())
                .register(new WorkspaceGates.SecretsInDiff())
                .register(new SecurityScan.Gate())
                .register(MavenGate.build(settings.maven()))
                .register(MavenGate.tests(settings.maven()));

        this.engine = Engine.builder()
                .events(events).artifacts(artifacts).plans(plans)
                .executors(executors).gates(gates)
                .policy(new YamlPolicyEngine(policy))
                .approvals(approvals)
                .faults(new FaultInjector(scenario.faults()))
                .options(settings.engineOptions())
                .sleeper(settings.sleeper())
                .build();
    }

    /** The fixed planning stage. Only the execution stage is planned by the model. */
    static Plan planningPlan() {
        RetryPolicy threeTries = RetryPolicy.attempts(3); // the first try plus two repairs
        return new Plan("planning", 1, List.of(
                TaskNode.builder("requirements", NodeType.REQUIREMENTS).executor(RequirementsAgent.KEY)
                        .description("Normalize the requirement into a testable spec")
                        .exitGates(OutputsPresentGate.ID, NoSecretsGate.ID, PlanningGates.SpecValid.ID)
                        .retryPolicy(threeTries).build(),
                TaskNode.builder("plan", NodeType.PLANNING).executor(PlannerAgent.KEY)
                        .fallbacks(PlannerAgent.Template.KEY)
                        .description("Propose the execution DAG; validated, then approved by a human")
                        .dependsOn("requirements")
                        .exitGates(OutputsPresentGate.ID, PlanningGates.PlanValid.ID)
                        .requiresApproval(true).retryPolicy(threeTries).build()));
    }

    /** Creates the planning run and its workspace. */
    public synchronized String start() {
        if (planningRunId != null) {
            throw new IllegalStateException("scenario already started as " + planningRunId);
        }
        String runId = engine.submit(planningPlan(), scenario.budget());
        contexts.put(new RunContext(runId, scenario.name(), scenario.requirement()));
        workspaces.create(runId, scenario.template());
        planningRunId = runId;
        return runId;
    }

    /**
     * Drives the scenario as far as it can go: to completion, a pause for a human, a safe-stop or a
     * failure. Call again after an approval to continue.
     */
    public synchronized RunOutcome drive() {
        if (planningRunId == null) {
            start();
        }
        if (executionRunId == null) {
            RunOutcome planning = engine.run(planningRunId);
            if (planning.status() != RunStatus.SUCCEEDED) {
                return planning;
            }
            executionRunId = startExecution();
        }
        RunOutcome outcome = engine.run(executionRunId);
        while (outcome.status() == RunStatus.FAILED && repairRounds < MAX_REPAIR_ROUNDS) {
            Optional<String> repaired = repairAfterIntegrationFailure(outcome);
            if (repaired.isEmpty()) {
                break;
            }
            repairRounds++;
            outcome = engine.run(executionRunId);
        }
        return outcome;
    }

    static final int MAX_REPAIR_ROUNDS = 2;
    private int repairRounds;

    /**
     * When the merged code fails its tests, the tests are the specification: the implementation nodes
     * are re-run with the failures as feedback. Bounded; after that a human decides.
     */
    private Optional<String> repairAfterIntegrationFailure(RunOutcome outcome) {
        Plan plan = plans.load(executionRunId).plan();
        for (String id : plan.topologicalOrder()) {
            TaskNode node = plan.node(id);
            if (node.type() != NodeType.JOIN || outcome.node(id) != com.agentic.orchestrator.domain.NodeStatus.FAILED) {
                continue;
            }
            String failure = engine.state(executionRunId).node(id).lastFailure();
            if (failure == null || !failure.contains("gate '" + MavenGate.TESTS + "'")
                    && !failure.contains("gate '" + MavenGate.BUILD + "'")) {
                return Optional.empty();
            }
            List<String> implementers = plan.topologicalOrder().stream()
                    .filter(n -> plan.downstreamInclusive(n).contains(id))
                    .filter(n -> plan.node(n).type() == NodeType.IMPLEMENT
                            || plan.node(n).type() == NodeType.SCHEMA_MIGRATION)
                    .toList();
            for (String impl : implementers) {
                engine.markStale(executionRunId, impl, "integration failed at '" + id + "' (repair round "
                        + (repairRounds + 1) + " of " + MAX_REPAIR_ROUNDS + ")",
                        "The merged code fails the independently written tests. The tests define the expected "
                                + "behaviour; change the implementation, not the tests.\n" + failure);
            }
            return implementers.isEmpty() ? Optional.empty() : Optional.of(id);
        }
        return Optional.empty();
    }

    private String startExecution() {
        String planHash = engine.state(planningRunId).node("plan").outputHashes().get(0);
        Artifact planArtifact = artifacts.get(planHash).orElseThrow();
        PlanProposal proposal = PlanningJson.parse(planArtifact.contentAsString(), PlanProposal.class);
        Plan plan = PlanBinder.bind(scenario.name() + "-execution", proposal, RetryPolicy.attempts(3));
        String runId = engine.submit(plan, scenario.budget());
        RunContext child = contexts.inherit(planningRunId, runId);
        child.plan(proposal);
        workspaces.share(planningRunId, runId);
        return runId;
    }

    /** Markdown evidence for the release node: metrics, approvals, gate failures and audit status. */
    String evidence(String runId) {
        List<Event> log = events.events(runId);
        RunMetrics m = RunMetrics.from(log);
        AuditVerifier.Result audit = AuditVerifier.verify(log);
        StringBuilder sb = new StringBuilder("## Evidence\n\n");
        sb.append("- Plan source: ").append(contexts.find(runId).flatMap(RunContext::plan).map(PlanProposal::source)
                .orElse("unknown")).append('\n');
        sb.append("- Attempts: ").append(m.attempts()).append(" (failed ").append(m.failedAttempts())
                .append("), retries ").append(m.retries()).append(", fallbacks ").append(m.fallbacks()).append('\n');
        sb.append("- Approvals so far: ").append(m.approvalsGranted()).append('\n');
        sb.append("- Tokens: ").append(m.tokens()).append(String.format(", cost $%.4f%n", m.costUsd()));
        sb.append("- Audit chain: ").append(audit.valid() ? "valid" : "BROKEN").append(", head ")
                .append(audit.headHash() == null ? "-" : audit.headHash().substring(0, 16)).append('\n');
        long gateFailures = log.stream().filter(e -> e.type() == EventType.GATE_EVALUATED
                && "false".equals(e.get("passed"))).count();
        sb.append("- Gate failures during the run (all later resolved): ").append(gateFailures).append('\n');
        return sb.toString();
    }

    public Scenario scenario() { return scenario; }
    public Engine engine() { return engine; }
    public EventStore events() { return events; }
    public ArtifactStore artifacts() { return artifacts; }
    public WorkspaceManager workspaces() { return workspaces; }
    public RunContext.Store contexts() { return contexts; }
    public Optional<String> planningRunId() { return Optional.ofNullable(planningRunId); }
    public Optional<String> executionRunId() { return Optional.ofNullable(executionRunId); }

    /** The run a human is currently dealing with. */
    public Optional<String> currentRunId() {
        return executionRunId != null ? Optional.of(executionRunId) : Optional.ofNullable(planningRunId);
    }

    public PlanStore plans() { return plans; }
}
