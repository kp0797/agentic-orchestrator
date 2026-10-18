package com.agentic.orchestrator.codebase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The brownfield scenario starts from a copy of the shortener with one planted defect. This keeps the
 * copy honest: it may differ from {@code shortener/} only in the files that carry the defect, so the
 * agents change the real service, not a toy.
 */
class BrownfieldTemplateTest {
    static final Path SHORTENER = Path.of("../shortener");
    static final Path TEMPLATE = Path.of("../scenarios/templates/shortener-v1");

    /** Files that carry the planted defect (or describe the service without its fix). */
    static final Set<String> PLANTED = Set.of(
            "src/main/java/com/agentic/shortener/link/LinkService.java",
            "src/main/java/com/agentic/shortener/link/LinkRepository.java",
            "src/main/resources/db/migration/V1__create_links.sql",
            "README.md");
    /** Tests that would have caught the defect, so they "did not exist yet". */
    static final Set<String> NOT_YET_WRITTEN = Set.of(
            "src/test/java/com/agentic/shortener/api/AliasConcurrencyTest.java",
            "src/test/java/com/agentic/shortener/PostgresRedisIntegrationTest.java");

    @Test
    void theTemplateIsTheShortenerExceptForThePlantedDefect() throws IOException {
        Map<String, String> real = files(SHORTENER);
        Map<String, String> copy = files(TEMPLATE);

        assertThat(copy.keySet()).containsAll(PLANTED);
        for (String f : real.keySet()) {
            if (NOT_YET_WRITTEN.contains(f)) {
                assertThat(copy).as(f).doesNotContainKey(f);
            } else if (!PLANTED.contains(f)) {
                assertThat(copy.get(f)).as("%s must match shortener/%s", f, f).isEqualTo(real.get(f));
            }
        }
        assertThat(copy.keySet()).allMatch(f -> real.containsKey(f) || f.equals("pom.xml"));
    }

    @Test
    void theDefectIsACheckThenInsertWithoutAUniqueConstraint() throws IOException {
        assertThat(Files.readString(TEMPLATE.resolve("src/main/java/com/agentic/shortener/link/LinkService.java")))
                .contains("if (links.findByCode(alias).isPresent())").doesNotContain("DuplicateKeyException");
        assertThat(Files.readString(TEMPLATE.resolve("src/main/resources/db/migration/V1__create_links.sql")))
                .doesNotContain("UNIQUE");
        assertThat(Files.readString(SHORTENER.resolve("src/main/resources/db/migration/V1__create_links.sql")))
                .contains("UNIQUE");
    }

    private static Map<String, String> files(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (!rel.startsWith("target/") && !rel.equals("pom.xml")) {
                    out.put(rel, Files.readString(p));
                }
            }
        }
        return out;
    }
}
