package com.agentic.orchestrator.eval;

import com.agentic.orchestrator.engine.RunOutcome;
import com.agentic.orchestrator.events.Event;
import com.agentic.orchestrator.evidence.EvidencePack;
import com.agentic.orchestrator.planning.PlanningJson;
import com.agentic.orchestrator.scenario.Orchestrator;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Runs each scenario N times, unattended, and reports reliability. Every run is a fresh orchestrator
 * with its own workspace and gets its own evidence pack; the summary is written as Markdown and JSON.
 * A run that throws is recorded as an ERROR result, not dropped: the harness reports failures, it
 * does not hide them.
 */
public final class EvalHarness {
    private final Function<String, Orchestrator> orchestrators;
    private final String mode;
    private final PrintStream out;

    /** @param orchestrators makes a fresh, unattended orchestrator for a scenario name */
    public EvalHarness(Function<String, Orchestrator> orchestrators, String mode, PrintStream out) {
        this.orchestrators = orchestrators;
        this.mode = mode;
        this.out = out;
    }

    public EvalReport run(List<String> scenarios, int runsEach, Path outDir) {
        if (runsEach < 1) {
            throw new IllegalArgumentException("runs must be at least 1");
        }
        List<ScenarioRunResult> results = new ArrayList<>();
        for (String scenario : scenarios) {
            for (int i = 1; i <= runsEach; i++) {
                out.printf("eval %s run %d/%d ... ", scenario, i, runsEach);
                ScenarioRunResult r = runOnce(scenario, i, outDir);
                results.add(r);
                out.printf("%s in %s%n", r.status(), EvalReport.fmt(r.wallTime()));
            }
        }
        EvalReport report = new EvalReport(mode, results);
        try {
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve("eval-summary.md"), report.markdown());
            Files.writeString(outDir.resolve("eval-summary.json"), PlanningJson.write(report.toMap()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return report;
    }

    private ScenarioRunResult runOnce(String scenario, int index, Path outDir) {
        Orchestrator o = null;
        try {
            o = orchestrators.apply(scenario);
            RunOutcome outcome = o.drive();
            Path evidence = EvidencePack.write(o, outDir.resolve(scenario + "-" + index), mode);
            return ScenarioRunResult.of(scenario, index, outcome.status().name(), outcome.reason(), logs(o),
                    o.supersededRuns().size(), evidence);
        } catch (RuntimeException e) {
            List<List<Event>> logs = o == null ? List.of() : logs(o);
            return ScenarioRunResult.of(scenario, index, "ERROR", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    logs, o == null ? 0 : o.supersededRuns().size(), null);
        }
    }

    private static List<List<Event>> logs(Orchestrator o) {
        List<List<Event>> logs = new ArrayList<>();
        o.planningRunId().ifPresent(id -> logs.add(o.events().events(id)));
        o.supersededRuns().forEach(id -> logs.add(o.events().events(id)));
        o.executionRunId().ifPresent(id -> logs.add(o.events().events(id)));
        return logs;
    }
}
