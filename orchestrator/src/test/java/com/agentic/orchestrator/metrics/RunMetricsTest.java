package com.agentic.orchestrator.metrics;

import com.agentic.orchestrator.events.EventType;
import com.agentic.orchestrator.events.InMemoryEventStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunMetricsTest {

    @Test
    void computesMttrWithAndWithoutHumanWait() {
        InMemoryEventStore s = new InMemoryEventStore();
        Instant t = Instant.parse("2026-10-17T10:00:00Z");
        s.append("r", EventType.RUN_STARTED, null, Map.of(), t);
        s.append("r", EventType.PLAN_ACCEPTED, null, Map.of("nodes", "1"), t);
        s.append("r", EventType.NODE_ATTEMPT_STARTED, "a", Map.of("attempt", "1"), t);
        s.append("r", EventType.NODE_ATTEMPT_FAILED, "a", Map.of("reason", "x"), t.plusSeconds(1));
        s.append("r", EventType.NODE_STATUS_CHANGED, "a", Map.of("to", "RETRYING"), t.plusSeconds(1));
        s.append("r", EventType.NODE_ATTEMPT_STARTED, "a", Map.of("attempt", "2"), t.plusSeconds(2));
        s.append("r", EventType.APPROVAL_REQUESTED, "a", Map.of("digest", "d"), t.plusSeconds(3));
        s.append("r", EventType.APPROVAL_GRANTED, "a", Map.of("digest", "d"), t.plusSeconds(8));
        s.append("r", EventType.NODE_STATUS_CHANGED, "a", Map.of("to", "SUCCEEDED"), t.plusSeconds(8));
        s.append("r", EventType.TOKENS_USED, "a", Map.of("tokens", "500", "costUsd", "0.01"), t.plusSeconds(8));
        s.append("r", EventType.RUN_SUCCEEDED, null, Map.of(), t.plusSeconds(9));

        RunMetrics m = RunMetrics.from(s.events("r"));
        assertThat(m.finalStatus()).isEqualTo("SUCCEEDED");
        assertThat(m.attempts()).isEqualTo(2);
        assertThat(m.failedAttempts()).isEqualTo(1);
        assertThat(m.retries()).isEqualTo(1);
        assertThat(m.mttr()).contains(Duration.ofSeconds(7));
        assertThat(m.mttrExcludingHumanWait()).contains(Duration.ofSeconds(2));
        assertThat(m.humanWait()).isEqualTo(Duration.ofSeconds(5));
        assertThat(m.endToEnd()).isEqualTo(Duration.ofSeconds(9));
        assertThat(m.tokens()).isEqualTo(500);
        assertThat(m.attemptSuccessRate()).isEqualTo(0.5);

        ReliabilityReport report = ReliabilityReport.of(List.of(m, m));
        assertThat(report.runSuccessRate()).isEqualTo(1.0);
        assertThat(report.p95Latency()).isEqualTo(Duration.ofSeconds(9));
    }
}
