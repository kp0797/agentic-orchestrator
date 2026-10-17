package com.agentic.orchestrator.events;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Needs Docker: run with {@code mvn verify -Ddocker=true}. */
@Tag("docker")
@Testcontainers
class JdbcEventStorePostgresTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void chainSurvivesPostgresTimestampPrecision() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute(Files.readString(Path.of("src/main/resources/db/migration/V1__run_events.sql")));
        }
        JdbcEventStore store = new JdbcEventStore(ds);
        store.append("r1", EventType.RUN_STARTED, null, Map.of("k", "v"), Instant.parse("2026-10-17T10:00:00.123456789Z"));
        store.append("r1", EventType.RUN_SUCCEEDED, null, Map.of(), Instant.now());
        assertThat(AuditVerifier.verify(store.events("r1")).valid()).isTrue();
    }
}
