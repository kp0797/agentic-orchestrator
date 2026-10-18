package com.agentic.orchestrator.eval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reliability across N runs of each scenario: what the README's numbers come from. */
public record EvalReport(String mode, List<ScenarioRunResult> runs) {

    public record Aggregate(String scenario, int runs, double successRate, double attemptSuccessRate,
                            double retriesPerRun, double recoveriesPerRun, double replansPerRun,
                            double humanDecisionsPerRun, Optional<Duration> meanMttr, Duration p50Wall,
                            Duration p95Wall, Duration minWall, Duration maxWall, double meanCostUsd,
                            long meanTokens) {}

    public List<String> scenarios() {
        return runs.stream().map(ScenarioRunResult::scenario).distinct().toList();
    }

    public Aggregate aggregate(String scenario) {
        List<ScenarioRunResult> rs = runs.stream().filter(r -> r.scenario().equals(scenario)).toList();
        if (rs.isEmpty()) {
            throw new IllegalArgumentException("no runs of " + scenario);
        }
        int n = rs.size();
        int attempts = rs.stream().mapToInt(ScenarioRunResult::attempts).sum();
        int failed = rs.stream().mapToInt(ScenarioRunResult::failedAttempts).sum();
        List<Duration> mttrs = rs.stream().flatMap(r -> r.mttr().stream()).toList();
        List<Duration> walls = rs.stream().map(ScenarioRunResult::wallTime).sorted().toList();
        return new Aggregate(scenario, n,
                rs.stream().filter(ScenarioRunResult::succeeded).count() / (double) n,
                attempts == 0 ? 0 : (attempts - failed) / (double) attempts,
                rs.stream().mapToInt(ScenarioRunResult::retries).sum() / (double) n,
                rs.stream().mapToInt(ScenarioRunResult::recoveredNodes).sum() / (double) n,
                rs.stream().mapToInt(ScenarioRunResult::replans).sum() / (double) n,
                rs.stream().mapToInt(ScenarioRunResult::humanDecisions).sum() / (double) n,
                mttrs.isEmpty() ? Optional.empty()
                        : Optional.of(Duration.ofMillis(mttrs.stream().mapToLong(Duration::toMillis).sum() / mttrs.size())),
                percentile(walls, 0.50), percentile(walls, 0.95), walls.get(0), walls.get(n - 1),
                rs.stream().mapToDouble(ScenarioRunResult::costUsd).sum() / n,
                rs.stream().mapToLong(ScenarioRunResult::tokens).sum() / n);
    }

    static Duration percentile(List<Duration> sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    public String markdown() {
        StringBuilder sb = new StringBuilder("# Eval summary\n\n");
        sb.append("Mode: **").append(mode).append("**. ");
        sb.append("replay".equalsIgnoreCase(mode)
                ? "Every run replays the same model answers from the scenario's cassette, so these numbers measure the orchestrator "
                + "(determinism, gates, recovery paths, real builds and tests), not model quality. Run eval in live mode "
                + "(docker compose --profile live run --rm eval-live) to measure how reliably the models complete "
                + "the same scenarios."
                : "Model answers were live, so success rate and retries include model variability.");
        sb.append("\n\n| Scenario | Runs | Success | Attempt success | Retries/run | Recoveries/run | Re-plans/run "
                + "| Approval decisions/run | MTTR | Wall p50 | Wall p95 | Mean cost |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (String s : scenarios()) {
            Aggregate a = aggregate(s);
            sb.append(String.format("| %s | %d | %.0f%% | %.0f%% | %.2f | %.2f | %.2f | %.2f | %s | %s | %s | $%.4f |%n",
                    s, a.runs(), a.successRate() * 100, a.attemptSuccessRate() * 100, a.retriesPerRun(),
                    a.recoveriesPerRun(), a.replansPerRun(), a.humanDecisionsPerRun(),
                    a.meanMttr().map(EvalReport::fmt).orElse("n/a"), fmt(a.p50Wall()), fmt(a.p95Wall()),
                    a.meanCostUsd()));
        }
        sb.append("\n## Runs\n\n| Scenario | # | Result | Attempts (failed) | Retries | Faults injected | Re-plans "
                + "| Wall time | Evidence |\n| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (ScenarioRunResult r : runs) {
            sb.append(String.format("| %s | %d | %s | %d (%d) | %d | %d | %d | %s | `%s` |%n", r.scenario(), r.index(),
                    r.status() + (r.reason().isBlank() || r.succeeded() ? "" : ": " + r.reason().replace("|", "/")),
                    r.attempts(), r.failedAttempts(), r.retries(), r.faultsInjected(), r.replans(), fmt(r.wallTime()),
                    r.evidence() == null ? "-" : r.evidence().toString()));
        }
        sb.append("\nDefinitions: a run succeeds when its final execution run succeeds. Attempt success is the share of "
                + "node attempts that passed all their gates. A recovery is a node that failed an attempt "
                + "and later succeeded. MTTR is the mean time from a node's first failed attempt to its success. Wall "
                + "time spans the scenario's first to last audit event, including any re-plans.\n");
        return sb.toString();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        List<Map<String, Object>> aggregates = new ArrayList<>();
        for (String s : scenarios()) {
            Aggregate a = aggregate(s);
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("scenario", s);
            x.put("runs", a.runs());
            x.put("successRate", a.successRate());
            x.put("attemptSuccessRate", a.attemptSuccessRate());
            x.put("retriesPerRun", a.retriesPerRun());
            x.put("recoveriesPerRun", a.recoveriesPerRun());
            x.put("replansPerRun", a.replansPerRun());
            x.put("humanDecisionsPerRun", a.humanDecisionsPerRun());
            x.put("meanMttrMs", a.meanMttr().map(Duration::toMillis).orElse(null));
            x.put("p50WallMs", a.p50Wall().toMillis());
            x.put("p95WallMs", a.p95Wall().toMillis());
            x.put("minWallMs", a.minWall().toMillis());
            x.put("maxWallMs", a.maxWall().toMillis());
            x.put("meanCostUsd", a.meanCostUsd());
            x.put("meanTokens", a.meanTokens());
            aggregates.add(x);
        }
        m.put("scenarios", aggregates);
        List<Map<String, Object>> rs = new ArrayList<>();
        for (ScenarioRunResult r : runs) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("scenario", r.scenario());
            x.put("index", r.index());
            x.put("status", r.status());
            x.put("reason", r.reason());
            x.put("runIds", r.runIds());
            x.put("replans", r.replans());
            x.put("attempts", r.attempts());
            x.put("failedAttempts", r.failedAttempts());
            x.put("retries", r.retries());
            x.put("faultsInjected", r.faultsInjected());
            x.put("recoveredNodes", r.recoveredNodes());
            x.put("humanDecisions", r.humanDecisions());
            x.put("clarifications", r.clarifications());
            x.put("wallTimeMs", r.wallTime().toMillis());
            x.put("mttrMs", r.mttr().map(Duration::toMillis).orElse(null));
            x.put("tokens", r.tokens());
            x.put("costUsd", r.costUsd());
            x.put("evidence", r.evidence() == null ? null : r.evidence().toString());
            rs.add(x);
        }
        m.put("runs", rs);
        return m;
    }

    static String fmt(Duration d) {
        return d.toMillis() < 1000 ? d.toMillis() + "ms" : String.format("%.1fs", d.toMillis() / 1000.0);
    }
}
