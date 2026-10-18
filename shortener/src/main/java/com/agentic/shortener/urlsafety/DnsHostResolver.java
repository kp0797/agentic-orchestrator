package com.agentic.shortener.urlsafety;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** System DNS with a time limit: a slow resolver must not hold a request thread indefinitely. */
public final class DnsHostResolver implements HostResolver {
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DnsHostResolver(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public List<InetAddress> resolve(String host) {
        Future<InetAddress[]> lookup = executor.submit(() -> InetAddress.getAllByName(host));
        try {
            return List.of(lookup.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            lookup.cancel(true);
            return List.of();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownHostException) {
                return List.of();
            }
            throw new IllegalStateException("DNS lookup failed for " + host, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }
}
