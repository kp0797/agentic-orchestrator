package com.agentic.shortener.clicks;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ClickRecorderTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void aFullQueueDropsClicksAndCountsThemInsteadOfBlocking() {
        ClickRecorder recorder = new ClickRecorder(mock(ClickRepository.class), 2, 10, meters);

        assertThat(recorder.record(click())).isTrue();
        assertThat(recorder.record(click())).isTrue();
        assertThat(recorder.record(click())).isFalse();

        assertThat(meters.counter("shortener.clicks.dropped").count()).isEqualTo(1);
        assertThat(meters.get("shortener.clicks.queued").gauge().value()).isEqualTo(2);
    }

    @Test
    void writesInBatches() {
        List<Integer> batchSizes = new ArrayList<>();
        ClickRepository repo = new ClickRepository(null, null) {
            @Override
            public void insertBatch(List<ClickEvent> events) {
                batchSizes.add(events.size());
            }
        };
        ClickRecorder recorder = new ClickRecorder(repo, 100, 4, meters);
        for (int i = 0; i < 10; i++) {
            recorder.record(click());
        }

        recorder.drainNow();

        assertThat(batchSizes).containsExactly(4, 4, 2);
        assertThat(meters.counter("shortener.clicks.recorded").count()).isEqualTo(10);
    }

    @Test
    void aFailedWriteIsCountedAndDoesNotStopTheRecorder() {
        ClickRepository repo = mock(ClickRepository.class);
        doThrow(new IllegalStateException("db down")).when(repo).insertBatch(anyList());
        ClickRecorder recorder = new ClickRecorder(repo, 100, 10, meters);
        recorder.record(click());

        recorder.drainNow();

        assertThat(meters.counter("shortener.clicks.failed").count()).isEqualTo(1);
        assertThat(recorder.record(click())).isTrue();
    }

    @Test
    void theBackgroundWorkerWritesWithoutBeingAsked() throws Exception {
        List<ClickEvent> written = new java.util.concurrent.CopyOnWriteArrayList<>();
        ClickRepository repo = new ClickRepository(null, null) {
            @Override
            public void insertBatch(List<ClickEvent> events) {
                written.addAll(events);
            }
        };
        ClickRecorder recorder = new ClickRecorder(repo, 100, 10, meters);
        recorder.start();
        try {
            recorder.record(click());
            long deadline = System.currentTimeMillis() + 5_000;
            while (written.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(written).hasSize(1);
        } finally {
            recorder.stop();
        }
        assertThat(recorder.isRunning()).isFalse();
    }

    private static ClickEvent click() {
        return new ClickEvent(1, Instant.parse("2026-10-18T10:00:00Z"), null);
    }
}
