package com.agentic.orchestrator.app;

import com.agentic.orchestrator.artifacts.ArtifactStore;
import com.agentic.orchestrator.artifacts.InMemoryArtifactStore;
import com.agentic.orchestrator.engine.ApprovalDecision;
import com.agentic.orchestrator.engine.ApprovalHandler;
import com.agentic.orchestrator.engine.EngineOptions;
import com.agentic.orchestrator.engine.PlanStore;
import com.agentic.orchestrator.engine.Sleeper;
import com.agentic.orchestrator.events.EventStore;
import com.agentic.orchestrator.events.InMemoryEventStore;
import com.agentic.orchestrator.gates.MavenGate;
import com.agentic.orchestrator.model.ModelGateway;
import com.agentic.orchestrator.model.ModelGateways;
import com.agentic.orchestrator.model.ModelSettings;
import com.agentic.orchestrator.model.live.SpringAiModelGateway;
import com.agentic.orchestrator.policy.PolicyLoader;
import com.agentic.orchestrator.scenario.Orchestrator;
import com.agentic.orchestrator.scenario.Scenario;
import com.agentic.orchestrator.scenario.ScenarioLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts scenarios and drives them in the background, so the console stays responsive while agents
 * work. A human decision is recorded and the scenario continues from where it paused.
 */
public final class RunService implements AutoCloseable {
    private final AgenticProperties props;
    private final EventStore events;
    private final ArtifactStore artifacts = new InMemoryArtifactStore();
    private final PlanStore plans = new PlanStore.InMemory();
    private final Map<String, Orchestrator> byRun = new ConcurrentHashMap<>();
    private final List<Orchestrator> all = new CopyOnWriteArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    public RunService(AgenticProperties props) {
        this(props, new InMemoryEventStore());
    }

    /** @param events the audit log: in memory by default, PostgreSQL with the 'postgres' profile */
    public RunService(AgenticProperties props, EventStore events) {
        this.props = props;
        this.events = events;
    }

    public List<Scenario> scenarios() {
        Path dir = Path.of(props.scenariosDir());
        return Files.isDirectory(dir) ? ScenarioLoader.loadAll(dir) : List.of();
    }

    /** Builds an orchestrator for a scenario; approvals come from the given handler. */
    public Orchestrator create(String scenarioName, ApprovalHandler approvals) {
        return create(scenarioName, approvals, List.of(), true);
    }

    /** @param extraFaults deliberate failures for this run only, recorded as injected in the audit log */
    /** @param scriptedStakeholder apply the scenario's scripted human messages (unattended runs only) */
    public Orchestrator create(String scenarioName, ApprovalHandler approvals,
                               List<com.agentic.orchestrator.engine.FaultSpec> extraFaults, boolean scriptedStakeholder) {
        Scenario found = scenarios().stream().filter(s -> s.name().equals(scenarioName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no scenario named " + scenarioName))
                .withFaults(extraFaults);
        Scenario scenario = scriptedStakeholder ? found : found.withoutScriptedStakeholder();
        ModelGateway gateway = ModelGateways.create(props.mode(), scenario.cassette(),
                () -> new SpringAiModelGateway(System.getenv("ANTHROPIC_API_KEY")));
        Orchestrator.Settings settings = new Orchestrator.Settings(
                Path.of(props.runsDir()).toAbsolutePath(),
                PolicyLoader.loadDirectory(Path.of(props.policiesDir())),
                modelSettings(),
                new MavenGate.Settings(System.getenv().getOrDefault("AGENTIC_MVN", "mvn"), props.mavenOffline(),
                        System.getProperty("maven.repo.local", System.getenv("AGENTIC_MVN_REPO")), Duration.ofMinutes(10)),
                EngineOptions.DEFAULT.withMaxParallel(props.maxParallel()),
                Sleeper.REAL);
        Orchestrator o = new Orchestrator(scenario, settings, gateway, events, artifacts, plans, approvals);
        all.add(o);
        return o;
    }

    /** Console entry point: starts in the background, humans approve in the browser. */
    public String start(String scenarioName) {
        // A person is at the console: they answer questions themselves, no scripted stakeholder.
        Orchestrator o = create(scenarioName, ApprovalHandler.MANUAL, List.of(), false);
        String runId = o.start();
        byRun.put(runId, o);
        continueInBackground(o);
        return runId;
    }

    public void decide(String runId, String nodeId, ApprovalDecision decision) {
        Orchestrator o = orchestrator(runId);
        o.engine().decide(runId, nodeId, decision);
        continueInBackground(o);
    }

    /**
     * A clarification from the console. Anything in flight is safe-stopped first; the clarification and
     * the continued run happen in the background.
     */
    public void clarify(String runId, String from, String text) {
        Orchestrator o = orchestrator(runId);
        o.executionRunId().ifPresent(id -> o.engine().requestStop(id, "clarification received from " + from));
        background.submit(() -> {
            try {
                o.clarify(from, text);
                o.drive();
                o.executionRunId().ifPresent(id -> byRun.put(id, o));
            } catch (RuntimeException e) {
                errors.add(o.scenario().name() + ": " + e.getMessage());
            }
        });
    }

    public void stop(String runId, String reason) {
        orchestrator(runId).engine().requestStop(runId, reason);
    }

    public void resume(String runId) {
        continueInBackground(orchestrator(runId));
    }

    private void continueInBackground(Orchestrator o) {
        background.submit(() -> {
            try {
                o.drive();
                o.executionRunId().ifPresent(id -> byRun.put(id, o));
            } catch (RuntimeException e) {
                errors.add(o.scenario().name() + ": " + e.getMessage());
            }
        });
    }

    public Orchestrator orchestrator(String runId) {
        Orchestrator o = byRun.get(runId);
        if (o == null) {
            for (Orchestrator candidate : all) {
                if (candidate.planningRunId().filter(runId::equals).isPresent()
                        || candidate.executionRunId().filter(runId::equals).isPresent()) {
                    byRun.put(runId, candidate);
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown run " + runId);
        }
        return o;
    }

    public List<Orchestrator> orchestrators() {
        return new ArrayList<>(all);
    }

    public List<String> errors() {
        return List.copyOf(errors);
    }

    /** Orchestrator metrics in Prometheus format, computed from the audit logs. */
    public String prometheusMetrics() {
        return new com.agentic.orchestrator.metrics.PrometheusExporter(events, runId -> {
            try {
                return Optional.of(plans.load(runId).plan());
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }).render();
    }

    public EventStore events() {
        return events;
    }

    public AgenticProperties properties() {
        return props;
    }

    public ModelSettings modelSettings() {
        ModelSettings defaults = ModelSettings.defaults();
        if (props.models().isEmpty()) {
            return defaults;
        }
        Map<String, ModelSettings.AgentModel> agents = new HashMap<>();
        props.models().forEach((agent, m) -> agents.put(agent, new ModelSettings.AgentModel(m.model(),
                Optional.ofNullable(m.maxTokens()).orElse(8192), m.temperature())));
        Map<String, ModelSettings.Price> prices = new HashMap<>(defaults.prices());
        props.prices().forEach((model, p) -> prices.put(model, new ModelSettings.Price(p.inputPerMTok(), p.outputPerMTok())));
        return new ModelSettings(agents, defaults.fallbackDefault(), defaults.fallbackChain(), prices);
    }

    @Override
    public void close() {
        background.shutdownNow();
    }
}
