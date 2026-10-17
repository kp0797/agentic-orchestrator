package com.agentic.orchestrator.metrics;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Aggregates many runs (the eval harness) into the numbers published in the README. */
public record ReliabilityReport(int runs, double runSuccessRate, double retriesPerRun, double rollbacksPerRun,
                                Optional<Duration> meanMttr, Duration p50Latency, Duration p95Latency) {

    public static ReliabilityReport of(List<RunMetrics> metrics) {
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("no runs");
        }
        int n = metrics.size();
        long ok = metrics.stream().filter(m -> "SUCCEEDED".equals(m.finalStatus())).count();
        double retries = metrics.stream().mapToInt(RunMetrics::retries).sum() / (double) n;
        double rollbacks = metrics.stream().mapToInt(RunMetrics::rollbacks).sum() / (double) n;
        List<Duration> mttrs = metrics.stream().flatMap(m -> m.mttr().stream()).toList();
        Optional<Duration> meanMttr = mttrs.isEmpty() ? Optional.empty()
                : Optional.of(Duration.ofMillis(mttrs.stream().mapToLong(Duration::toMillis).sum() / mttrs.size()));
        List<Duration> latencies = metrics.stream().map(RunMetrics::endToEnd).sorted().toList();
        return new ReliabilityReport(n, ok / (double) n, retries, rollbacks, meanMttr,
                percentile(latencies, 0.50), percentile(latencies, 0.95));
    }

    static Duration percentile(List<Duration> sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
