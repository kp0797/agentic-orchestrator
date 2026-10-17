package com.agentic.orchestrator.events;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real migration against H2 in PostgreSQL mode; {@code JdbcEventStorePostgresTest} uses Postgres. */
class JdbcEventStoreTest {
    private JdbcEventStore store;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            String sql = Files.readString(Path.of("src/main/resources/db/migration/V1__run_events.sql"));
            for (String stmt : sql.split(";")) {
                String s = stmt.replaceAll("(?m)^--.*$", "").trim();
                if (!s.isEmpty()) {
                    c.createStatement().execute(s);
                }
            }
        }
        store = new JdbcEventStore(ds);
    }

    @Test
    void persistsChainThatStillVerifiesAfterRoundTrip() {
        Instant t = Instant.parse("2026-10-17T10:00:00.123456789Z");
        store.append("r1", EventType.RUN_STARTED, null, Map.of("planId", "p;=\\x\nline2"), t);
        store.append("r1", EventType.NODE_STATUS_CHANGED, "a", Map.of("from", "PENDING", "to", "READY"), t);
        store.append("r2", EventType.RUN_STARTED, null, Map.of(), t);

        var events = store.events("r1");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("planId")).isEqualTo("p;=\\x\nline2");
        assertThat(AuditVerifier.verify(events).valid()).isTrue();
        assertThat(store.runIds()).containsExactlyInAnyOrder("r1", "r2");
    }
}
