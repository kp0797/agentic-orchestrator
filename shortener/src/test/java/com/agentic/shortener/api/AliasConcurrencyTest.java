package com.agentic.shortener.api;

import com.agentic.shortener.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The property the brownfield scenario's planted bug breaks: however many requests race for one
 * alias, exactly one gets it and the rest get 409, never a 500 or a second row.
 */
class AliasConcurrencyTest extends ApiTestBase {

    @Test
    void concurrentRequestsForOneAliasProduceExactlyOneLink() throws Exception {
        String alias = uniqueAlias();
        int requests = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            String body = "{\"url\":\"https://example.com/%d\",\"alias\":\"%s\"}".formatted(i, alias);
            calls.add(() -> {
                start.await();
                return mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus();
            });
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            List<Future<Integer>> futures = calls.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
        }

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(requests - 1);
    }
}
