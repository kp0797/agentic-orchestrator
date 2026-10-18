package com.agentic.shortener;

import com.agentic.shortener.clicks.ClickRecorder;
import com.agentic.shortener.support.TestBeans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The same flows against real PostgreSQL 16 and Redis 7: migrations, timestamp and date handling,
 * the unique-constraint translation, and the Redis cache. Needs Docker: run with -Ddocker=true
 * (docker compose run --rm test-docker).
 */
@Tag("docker")
@Testcontainers
@SpringBootTest(properties = "shortener.cache.type=redis")
@AutoConfigureMockMvc
@Import(TestBeans.class)
class PostgresRedisIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    ClickRecorder clicks;

    @Test
    void createRedirectThroughRedisAndReadStats() throws Exception {
        JsonNode link = create("https://example.com/pg");
        String code = link.get("code").asText();

        mvc.perform(get("/" + code).header("Referer", "https://ref.example/x")).andExpect(status().isFound());
        assertThat(redis.hasKey("link:" + code)).isTrue();
        mvc.perform(get("/" + code)).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/pg"));
        clicks.drainNow();

        mvc.perform(get("/api/links/" + code + "/stats").header("X-Management-Token", link.get("managementToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(2))
                .andExpect(jsonPath("$.clicksPerDay[29].clicks").value(2))
                .andExpect(jsonPath("$.topReferrers[0].clicks").value(1));
    }

    @Test
    void aliasRaceOnPostgresGivesExactlyOneWinner() throws Exception {
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(12)) {
            for (int i = 0; i < 12; i++) {
                results.add(pool.submit(() -> mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"url\":\"https://example.com\",\"alias\":\"pg-race\"}"))
                        .andReturn().getResponse().getStatus()));
            }
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(11);
    }

    private JsonNode create(String url) throws Exception {
        return json.readTree(mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"%s\"}".formatted(url)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
}
