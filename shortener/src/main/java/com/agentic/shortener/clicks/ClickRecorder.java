package com.agentic.shortener.clicks;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Records clicks off the redirect path. A redirect only offers an event to a bounded queue and
 * never waits: when the queue is full the click is dropped and counted
 * ({@code shortener.clicks.dropped}). A background worker writes events in batches.
 *
 * <p>Trade-off: statistics are best-effort. Clicks still queued when the process dies are lost;
 * a durable log (Kafka, Redis streams) would fix that at the cost of another dependency.
 */
public class ClickRecorder implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ClickRecorder.class);

    private final ClickRepository repository;
    private final BlockingQueue<ClickEvent> queue;
    private final int batchSize;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final Counter recorded;
    private final Counter dropped;
    private final Counter failed;
    private volatile boolean running;
    private Thread worker;

    public ClickRecorder(ClickRepository repository, int capacity, int batchSize, MeterRegistry meters) {
        this.repository = repository;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.batchSize = batchSize;
        this.recorded = Counter.builder("shortener.clicks.recorded").register(meters);
        this.dropped = Counter.builder("shortener.clicks.dropped")
                .description("clicks not recorded because the queue was full").register(meters);
        this.failed = Counter.builder("shortener.clicks.failed")
                .description("clicks lost because a batch write failed").register(meters);
        Gauge.builder("shortener.clicks.queued", queue, BlockingQueue::size).register(meters);
    }

    /** Never blocks. Returns false when the click was dropped. */
    public boolean record(ClickEvent event) {
        if (queue.offer(event)) {
            return true;
        }
        dropped.increment();
        return false;
    }

    /** Writes everything queued so far, on the caller's thread. Used at shutdown and by tests. */
    public void drainNow() {
        writeLock.lock();
        try {
            List<ClickEvent> batch = new ArrayList<>();
            while (queue.drainTo(batch, batchSize) > 0) {
                write(batch);
                batch.clear();
            }
        } finally {
            writeLock.unlock();
        }
    }

    private void runWorker() {
        List<ClickEvent> batch = new ArrayList<>(batchSize);
        while (running) {
            writeLock.lock();
            try {
                ClickEvent first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, batchSize - 1);
                    write(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                writeLock.unlock();
            }
        }
    }

    private void write(List<ClickEvent> batch) {
        try {
            repository.insertBatch(batch);
            recorded.increment(batch.size());
        } catch (RuntimeException e) {
            failed.increment(batch.size());
            log.warn("could not record {} clicks: {}", batch.size(), e.toString());
        }
    }

    @Override
    public void start() {
        running = true;
        worker = Thread.ofVirtual().name("click-recorder").start(this::runWorker);
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        drainNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public int queued() {
        return queue.size();
    }
}
