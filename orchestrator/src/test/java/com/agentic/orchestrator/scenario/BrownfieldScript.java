package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.planning.PlanningJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Scripted agent answers for the brownfield scenario (fixing the alias race in the shortener). They are
 * what a capable model could answer, written by hand so the scenario replays without a model. The
 * migration agent's first answer is deliberately wrong (it edits the applied V1 migration) so the run
 * shows a deterministic gate catching an agent mistake and the retry fixing it.
 */
final class BrownfieldScript {
    private BrownfieldScript() {}

    static Map<String, Function<Integer, String>> answers() {
        Map<String, Function<Integer, String>> a = new HashMap<>();
        a.put("requirements", n -> SPEC);
        a.put("planner", n -> PLAN);
        a.put("architect", n -> DESIGN);
        a.put("coder:migrate-unique-codes", n -> n == 1 ? MIGRATION_EDITS_APPLIED_V1 : MIGRATION);
        a.put("coder:fix-alias-race", n -> FIX);
        a.put("tester", n -> TESTS);
        a.put("docs", n -> DOCS);
        return a;
    }

    static final String SPEC = json(Map.of(
            "title", "Make link codes unique under concurrency (INC-2291)",
            "summary", "Creating links checks whether an alias is free and then inserts it, with no database "
                    + "constraint, so concurrent requests can store the same code twice; lookups then fail with 500. "
                    + "Make the database enforce uniqueness, map the conflict to 409, and repair stored duplicates.",
            "goals", List.of("An alias is taken at most once, whatever the concurrency",
                    "Existing duplicate rows are repaired without affecting other links",
                    "A regression test reproduces the race"),
            "nonFunctional", List.of("No API change", "Existing tests keep passing",
                    "The repair runs as a Flyway migration on PostgreSQL and H2"),
            "acceptanceCriteria", List.of(
                    Map.of("id", "AC1", "given", "16 concurrent create requests for the same alias",
                            "when", "they are processed",
                            "then", "exactly one gets 201 and the other 15 get 409 Conflict"),
                    Map.of("id", "AC2", "given", "the links table already holds several rows with the same code",
                            "when", "the new migration runs",
                            "then", "only the earliest row for that code remains, other links are untouched, and "
                                    + "the database rejects any new duplicate code")),
            "ambiguities", List.of(Map.of("question", "Which of the duplicate rows should survive?",
                    "proposedDefault", "The earliest (lowest id); its owner created it first. Later duplicates and "
                            + "their click rows are deleted. Their redirects already failed, so nothing working is lost.",
                    "confidence", "MEDIUM")),
            "assumptions", List.of("Duplicates exist only for codes created concurrently"),
            "outOfScope", List.of("Notifying owners of deleted duplicates")));

    static final String PLAN = "Plan for the incident fix:\n" + json(Map.of(
            "rationale", "Root cause is a check-then-insert without a unique constraint. The schema change and "
                    + "the service change touch different files and can run in parallel after the ADR; the "
                    + "regression tests are written from the criteria in parallel with both.",
            "nodes", List.of(
                    node("design", "DESIGN", "ADR in docs/decisions: root cause, fix, which duplicate survives",
                            List.of(), "MEDIUM", false, List.of("AC1", "AC2")),
                    node("migrate-unique-codes", "SCHEMA_MIGRATION",
                            "V3 migration: delete later duplicate rows, add a unique constraint on links.code",
                            List.of("design"), "HIGH", true, List.of("AC2")),
                    node("fix-alias-race", "IMPLEMENT",
                            "Package com.agentic.shortener.link: insert and map DuplicateKeyException to 409 (alias) "
                                    + "or retry (generated code) instead of checking first",
                            List.of("design"), "MEDIUM", false, List.of("AC1")),
                    node("regression-tests", "TEST", "Concurrency test for AC1 and a migration test for AC2",
                            List.of("design"), "LOW", false, List.of("AC1", "AC2")),
                    node("integrate", "JOIN", "Merge and run the full suite plus the regression check",
                            List.of("migrate-unique-codes", "fix-alias-race", "regression-tests"), "MEDIUM", false,
                            List.of()),
                    node("docs", "DOCS", "README design decisions", List.of("integrate"), "LOW", false, List.of()),
                    node("security-scan", "SECURITY_SCAN", "Scan the merged change", List.of("integrate"), "LOW",
                            false, List.of()),
                    node("release-readiness", "RELEASE_READINESS", "Evidence and release approval",
                            List.of("docs", "security-scan"), "HIGH", true, List.of()))));

    static final String DESIGN = edits("ADR for INC-2291: the database enforces code uniqueness; the service "
                    + "inserts and handles the conflict.",
            List.of(Map.of("path", "docs/decisions/0001-unique-link-codes.md", "action", "create", "content", """
                    # 1. The database enforces unique link codes

                    Status: accepted (INC-2291)

                    ## Context
                    `LinkService.create` checks `findByCode` and then inserts. `links.code` has only a plain index,
                    so two concurrent requests for one alias both pass the check and both insert. Afterwards every
                    lookup of that code (`findByCode(...).optional()`) fails because it returns two rows: HTTP 500
                    on redirect and on stats.

                    ## Decision
                    - Migration V3 deletes later duplicates (the earliest row per code survives; click rows of the
                      deleted links go with them by cascade), adds `uk_links_code UNIQUE (code)` and drops the
                      now redundant index. V1 is not edited: it has already run in production.
                    - `LinkService` inserts first and treats `DuplicateKeyException` as the answer: 409 for an
                      alias, another attempt for a generated code. No check-then-insert anywhere.

                    ## Consequences
                    Owners of deleted duplicates lose those links; their redirects were already failing.
                    Uniqueness no longer depends on application code being correct.
                    """)),
            List.of(Map.of("title", "Which duplicate survives", "context", "AC2",
                    "options", List.of("earliest row", "latest row", "rename duplicates"),
                    "choice", "earliest row",
                    "rationale", "It was created first; renaming would give owners codes they never saw")));

    /** Deliberately wrong: edits the applied V1 migration. The migrations-immutable gate must reject it. */
    static final String MIGRATION_EDITS_APPLIED_V1 = edits("Add the unique constraint to the links table.",
            List.of(Map.of("path", "src/main/resources/db/migration/V1__create_links.sql", "action", "patch",
                    "search", "    token_hash  CHAR(64)      NOT NULL\n);",
                    "replace", "    token_hash  CHAR(64)      NOT NULL,\n    CONSTRAINT uk_links_code UNIQUE (code)\n);")),
            List.of());

    static final String MIGRATION = edits("New migration V3 repairs stored duplicates and adds the unique "
                    + "constraint; V1 is left as applied.",
            List.of(Map.of("path", "src/main/resources/db/migration/V3__unique_link_codes.sql", "action", "create",
                    "content", """
                            -- INC-2291: concurrent requests for one alias stored the same code more than once.
                            -- Keep the earliest row per code; later duplicates (and, by cascade, their clicks) go.
                            DELETE FROM links WHERE id IN (
                                SELECT dup.id FROM links dup
                                JOIN links keep ON keep.code = dup.code AND keep.id < dup.id);

                            ALTER TABLE links ADD CONSTRAINT uk_links_code UNIQUE (code);

                            -- The unique constraint's index replaces the plain one.
                            DROP INDEX ix_links_code;
                            """)),
            List.of());

    static final String LINK_SERVICE = "src/main/java/com/agentic/shortener/link/LinkService.java";
    static final String LINK_REPOSITORY = "src/main/java/com/agentic/shortener/link/LinkRepository.java";

    static final String FIX = edits("Insert first and let the unique constraint decide: DuplicateKeyException "
                    + "becomes 409 for an alias and a retry for a generated code.",
            List.of(
                    Map.of("path", LINK_SERVICE, "action", "patch",
                            "search", """
                                    if (links.findByCode(alias).isPresent()) {
                                        throw ApiException.aliasTaken(alias);
                                    }
                                    return new CreatedLink(links.insert(alias, url, now, expiresAt, tokenHash), token);
                                    """.indent(12),
                            "replace", """
                                    try {
                                        // Insert and let the unique constraint decide. Two concurrent requests for the same
                                        // alias: exactly one insert succeeds, the other gets 409.
                                        return new CreatedLink(links.insert(alias, url, now, expiresAt, tokenHash), token);
                                    } catch (DuplicateKeyException e) {
                                        throw ApiException.aliasTaken(alias);
                                    }
                                    """.indent(12)),
                    Map.of("path", LINK_SERVICE, "action", "patch",
                            "search", """
                                    if (links.findByCode(code).isPresent()) {
                                        log.info("generated code collided, retrying (attempt {})", attempt);
                                        continue;
                                    }
                                    return new CreatedLink(links.insert(code, url, now, expiresAt, tokenHash), token);
                                    """.indent(12),
                            "replace", """
                                    try {
                                        return new CreatedLink(links.insert(code, url, now, expiresAt, tokenHash), token);
                                    } catch (DuplicateKeyException e) {
                                        log.info("generated code collided, retrying (attempt {})", attempt);
                                    }
                                    """.indent(12)),
                    Map.of("path", LINK_SERVICE, "action", "patch",
                            "search", "import org.springframework.stereotype.Service;",
                            "replace", "import org.springframework.dao.DuplicateKeyException;\n"
                                    + "import org.springframework.stereotype.Service;"),
                    Map.of("path", LINK_REPOSITORY, "action", "patch",
                            "search", "    /** Inserts a link and returns it with its id. */\n",
                            "replace", """
                                    /**
                                     * Inserts a link and returns it with its id.
                                     *
                                     * @throws DuplicateKeyException when the code is already taken. Callers rely on this rather than
                                     *                               checking first: a check-then-insert lets two requests both pass.
                                     */
                                    """.indent(4)),
                    Map.of("path", LINK_REPOSITORY, "action", "patch",
                            "search", "import org.springframework.jdbc.core.simple.JdbcClient;",
                            "replace", "import org.springframework.dao.DuplicateKeyException;\n"
                                    + "import org.springframework.jdbc.core.simple.JdbcClient;")),
            List.of(Map.of("title", "Generated codes", "context", "same check-then-insert pattern",
                    "options", List.of("leave as is", "insert and retry on conflict"),
                    "choice", "insert and retry on conflict",
                    "rationale", "The same race exists for generated codes; one pattern everywhere")));

    static final String TESTS = edits("Regression tests: a 16-request race for one alias (AC1) and the V3 "
                    + "repair of stored duplicates (AC2).",
            List.of(
                    Map.of("path", "src/test/java/com/agentic/shortener/api/AliasConcurrencyTest.java", "action",
                            "create", "content", read("../shortener/src/test/java/com/agentic/shortener/api/"
                                    + "AliasConcurrencyTest.java")
                                    .replace("void concurrentRequestsForOneAliasProduceExactlyOneLink()",
                                            "void ac1_concurrentRequestsForOneAliasProduceExactlyOneLink()")),
                    Map.of("path", "src/test/java/com/agentic/shortener/link/DuplicateCodesMigrationTest.java",
                            "action", "create", "content", """
                                    package com.agentic.shortener.link;

                                    import org.flywaydb.core.Flyway;
                                    import org.junit.jupiter.api.Test;
                                    import org.springframework.dao.DuplicateKeyException;
                                    import org.springframework.jdbc.core.JdbcTemplate;
                                    import org.springframework.jdbc.datasource.DriverManagerDataSource;

                                    import static org.assertj.core.api.Assertions.assertThat;
                                    import static org.assertj.core.api.Assertions.assertThatThrownBy;

                                    /** AC2: duplicates already stored are repaired by migration, and new ones are rejected. */
                                    class DuplicateCodesMigrationTest {

                                        @Test
                                        void ac2_storedDuplicatesAreRepairedAndCodesBecomeUnique() {
                                            DriverManagerDataSource db = new DriverManagerDataSource("jdbc:h2:mem:dup-" + System.nanoTime()
                                                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
                                            // The production schema before the fix, with the duplicates the race left behind.
                                            Flyway.configure().dataSource(db).locations("classpath:db/migration").target("2").load().migrate();
                                            JdbcTemplate jdbc = new JdbcTemplate(db);
                                            insert(jdbc, "dup-alias", "https://example.com/first");
                                            insert(jdbc, "dup-alias", "https://example.com/second");
                                            insert(jdbc, "other", "https://example.com/other");

                                            Flyway.configure().dataSource(db).locations("classpath:db/migration").load().migrate();

                                            assertThat(jdbc.queryForList("SELECT target_url FROM links WHERE code = 'dup-alias'", String.class))
                                                    .containsExactly("https://example.com/first");
                                            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM links WHERE code = 'other'", Integer.class))
                                                    .isEqualTo(1);
                                            assertThatThrownBy(() -> insert(jdbc, "other", "https://example.com/again"))
                                                    .isInstanceOf(DuplicateKeyException.class);
                                        }

                                        private static void insert(JdbcTemplate jdbc, String code, String url) {
                                            jdbc.update("INSERT INTO links (code, target_url, created_at, token_hash) "
                                                    + "VALUES (?, ?, CURRENT_TIMESTAMP, ?)", code, url, "0".repeat(64));
                                        }
                                    }
                                    """)),
            List.of());

    static final String DOCS = edits("README: record that the database enforces unique codes.",
            List.of(Map.of("path", "README.md", "action", "patch",
                    "search", "| Random 7-character base62 codes, checked for use before insert | Not enumerable; 3.5 trillion codes. |\n",
                    "replace", "| Random 7-character base62 codes, retried on collision | Not enumerable; 3.5 trillion codes. "
                            + "The unique constraint, not a pre-check, guarantees uniqueness. |\n"
                            + "| Alias conflicts handled by inserting and catching the unique violation | A check-then-insert "
                            + "let two concurrent requests both pass (INC-2291, docs/decisions/0001). |\n")),
            List.of());

    private static Map<String, Object> node(String id, String type, String description, List<String> dependsOn,
                                            String risk, boolean approval, List<String> criteria) {
        return Map.of("id", id, "type", type, "description", description, "dependsOn", dependsOn, "risk", risk,
                "requiresApproval", approval, "acceptanceCriteria", criteria);
    }

    private static String edits(String summary, List<? extends Map<String, ?>> edits,
                                List<? extends Map<String, ?>> decisions) {
        return json(Map.of("summary", summary, "edits", edits, "decisions", decisions));
    }

    private static String json(Object value) {
        return PlanningJson.write(value);
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
