package com.agentic.orchestrator.codebase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodebaseAnalysisTest {
    static final String INCIDENT = """
            Users get HTTP 500 on some short links created with a custom alias. The links table has several rows
            with the same code. Make an alias impossible to take twice, even under concurrent requests.
            """;

    @TempDir
    Path dir;

    @Test
    void indexesTypesReferencesEndpointsTablesAndMigrations() throws Exception {
        write("src/main/java/x/api/ThingController.java", """
                package x.api;
                import org.springframework.web.bind.annotation.*;
                import x.core.ThingService;
                @RestController @RequestMapping("/api/things")
                public class ThingController {
                    private final ThingService service;
                    ThingController(ThingService s) { this.service = s; }
                    @GetMapping("/{id}") public String get(String id) { return service.find(id); }
                    @PostMapping public void create() { }
                }
                """);
        write("src/main/java/x/core/ThingService.java", """
                package x.core;
                public class ThingService {
                    private final ThingRepository repo = new ThingRepository();
                    public String find(String id) { return repo.load(id); }
                }
                """);
        write("src/main/java/x/core/ThingRepository.java", """
                package x.core;
                class ThingRepository {
                    String load(String id) { return "SELECT name FROM things WHERE id = ?" + id; }
                    void log() { System.out.println("loaded from the cache"); }
                }
                """);
        write("src/main/resources/db/migration/V1__things.sql", "CREATE TABLE things (id BIGINT);");
        write("src/main/resources/db/migration/V2__index.sql", "CREATE INDEX ix ON things (id);");
        write("src/test/java/x/ThingHttpTest.java", """
                package x;
                class ThingHttpTest { void t() { call("/api/things/1"); } void call(String p) {} }
                """);
        write("src/main/java/x/Broken.java", "class Broken { void (");

        RepoIndex index = RepoIndex.build(dir);

        RepoIndex.TypeInfo controller = index.type("ThingController").orElseThrow();
        assertThat(controller.endpoints()).containsExactly("GET /api/things/{id}", "POST /api/things");
        assertThat(controller.references()).containsExactly("ThingService");
        assertThat(index.type("ThingRepository").orElseThrow().tables()).containsExactly("things");
        assertThat(index.migrations()).extracting(RepoIndex.Migration::version).containsExactly(1, 2);
        assertThat(index.nextMigrationVersion()).isEqualTo(3);
        assertThat(index.unparsable()).containsExactly("src/main/java/x/Broken.java");
        assertThat(index.dependentsOf(java.util.List.of("ThingRepository"))).containsExactly("ThingService");

        ImpactReport report = ImpactAnalyzer.analyze(index, "Loading a thing by id returns the wrong name");
        assertThat(report.seeds()).extracting(ImpactReport.Item::type).contains("x.core.ThingRepository");
        assertThat(report.impacted()).extracting(ImpactReport.Item::type).contains("x.core.ThingService",
                "x.api.ThingController");
        assertThat(report.tests()).extracting(ImpactReport.Item::reason).containsExactly("calls /api/things/1");
        assertThat(report.markdown()).contains("never edit them", "The next migration is V3", "Broken.java");
    }

    @Test
    void anEmptyProjectIsReportedAsNew() {
        ImpactReport report = ImpactAnalyzer.analyze(RepoIndex.build(dir), INCIDENT);
        assertThat(report.emptyProject()).isTrue();
        assertThat(report.markdown()).startsWith("New project");
    }

    @Test
    void theBrownfieldIncidentPointsAtTheAliasPathOfTheShortener() {
        ImpactReport report = ImpactAnalyzer.analyze(RepoIndex.build(Path.of("../scenarios/templates/shortener-v1")),
                INCIDENT);

        assertThat(report.seeds()).extracting(ImpactReport.Item::type)
                .contains("com.agentic.shortener.link.AliasPolicy", "com.agentic.shortener.link.LinkRepository");
        assertThat(report.impacted()).extracting(ImpactReport.Item::type)
                .contains("com.agentic.shortener.link.LinkService", "com.agentic.shortener.api.LinkController")
                .doesNotContain("com.agentic.shortener.urlsafety.DnsHostResolver");
        assertThat(report.tables()).containsExactly("links");
        assertThat(report.nextMigrationVersion()).isEqualTo(3);
        assertThat(report.tests()).extracting(ImpactReport.Item::type)
                .contains("com.agentic.shortener.api.CreateLinkApiTest", "com.agentic.shortener.link.AliasPolicyTest");
        assertThat(report.risks()).anyMatch(r -> r.contains("rows already stored"));
    }

    @Test
    void wordsAreSplitFromIdentifiersAndNormalised() {
        assertThat(ImpactAnalyzer.split("findByCode")).containsExactly("find", "by", "code");
        assertThat(ImpactAnalyzer.split("URLSafetyValidator")).containsExactly("url", "safety", "validator");
        assertThat(ImpactAnalyzer.words("Aliases and the links table")).contains("alias", "link").doesNotContain("the");
        assertThat(ImpactAnalyzer.staticPrefix("GET /api/links/{code}/stats")).isEqualTo("/api/links/");
    }

    private void write(String rel, String content) throws Exception {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
