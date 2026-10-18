package com.agentic.orchestrator.app;

import com.agentic.orchestrator.events.EventType;
import com.agentic.orchestrator.events.JdbcEventStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With the 'postgres' profile the application keeps its audit log in the database: Flyway creates
 * the table at startup and the run service writes through the JDBC store. H2 in PostgreSQL mode
 * stands in for PostgreSQL here; JdbcEventStorePostgresTest covers the real database.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=postgres",
        "spring.datasource.url=jdbc:h2:mem:orchestrator;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.main.web-application-type=none"})
class PostgresProfileTest {
    @Autowired
    RunService runs;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theAuditLogIsStoredInTheDatabase() {
        assertThat(runs.events()).isInstanceOf(JdbcEventStore.class);

        runs.events().append("run-db", EventType.RUN_STARTED, null, Map.of("planId", "p"), Instant.now());

        assertThat(runs.events().events("run-db")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_events WHERE run_id = 'run-db'", Integer.class))
                .isEqualTo(1);
    }
}
