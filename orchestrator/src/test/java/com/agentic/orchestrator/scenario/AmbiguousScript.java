package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.planning.PlanningJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Scripted agent answers for the ambiguous scenario (unique visitors). Written by hand so the scenario
 * replays without a model. The requirements agent answers three times: with an open privacy question,
 * after the product owner's answers, and after the design review adds per-referrer visitors. The
 * implementation and tests it delivers are in src/test/resources/scripted/ambiguous and were checked
 * against the real shortener before being scripted.
 */
final class AmbiguousScript {
    static final Path FILES = Path.of("src/test/resources/scripted/ambiguous");
    static final Path SHORTENER = Path.of("../shortener");

    private AmbiguousScript() {}

    static Map<String, Function<Integer, String>> answers() {
        Map<String, Function<Integer, String>> a = new HashMap<>();
        a.put("requirements", n -> n == 1 ? SPEC_WITH_OPEN_QUESTION : n == 2 ? SPEC_ANSWERED : SPEC_WITH_REFERRERS);
        a.put("planner", n -> n == 1 ? plan(false) : plan(true));
        a.put("architect", n -> design(n > 1));
        a.put("coder:add-visitor-hash", n -> edits("Migration V3 adds a nullable visitor_hash column to click_events.",
                files("src/main/resources/db/migration/V3__click_visitor_hash.sql")));
        a.put("coder:unique-visitors", n -> edits("Redirects record a daily-salted visitor hash; stats count "
                        + "distinct hashes per day and per referrer.",
                files("src/main/java/com/agentic/shortener/clicks/ClickEvent.java",
                        "src/main/java/com/agentic/shortener/clicks/VisitorHasher.java",
                        "src/main/java/com/agentic/shortener/clicks/ClickConfig.java",
                        "src/main/java/com/agentic/shortener/clicks/ClickRepository.java",
                        "src/main/java/com/agentic/shortener/redirect/RedirectService.java",
                        "src/main/java/com/agentic/shortener/api/RedirectController.java",
                        "src/main/java/com/agentic/shortener/stats/LinkStats.java",
                        "src/main/java/com/agentic/shortener/stats/StatsService.java")));
        a.put("tester:visitor-tests", n -> edits("AC1 and AC2 through the HTTP API and the database.",
                files("src/test/java/com/agentic/shortener/api/UniqueVisitorsApiTest.java")));
        a.put("tester:referrer-visitor-tests", n -> edits("AC3 through the HTTP API.",
                files("src/test/java/com/agentic/shortener/api/ReferrerVisitorsApiTest.java")));
        a.put("docs", n -> json(Map.of("summary", "README: stats now include unique visitors.",
                "edits", List.of(Map.of("path", "README.md", "action", "patch",
                        "search", "top 10 referrer hosts (`(direct)` for none).",
                        "replace", "top 10 referrer hosts (`(direct)` for none). Each day and each referrer also "
                                + "shows `uniqueVisitors`, counted with a hash salted per UTC day; no IP address or "
                                + "user agent is stored.")),
                "decisions", List.of())));
        return a;
    }

    private static final Map<String, Object> AC1 = Map.of("id", "AC1",
            "given", "one link visited 4 times on one UTC day by 3 distinct visitors (IP address and user agent)",
            "when", "the owner reads the link's statistics",
            "then", "that day shows clicks 4 and uniqueVisitors 3");
    private static final Map<String, Object> AC2 = Map.of("id", "AC2",
            "given", "any redirect",
            "when", "the click is stored",
            "then", "neither the IP address nor the user agent is stored, and the stored visitor hash is salted "
                    + "with a random per-day salt that is never stored");
    private static final Map<String, Object> AC3 = Map.of("id", "AC3",
            "given", "3 clicks from news.example by 2 visitors and 1 click from blog.example",
            "when", "the owner reads the link's statistics",
            "then", "topReferrers shows news.example with clicks 3 and uniqueVisitors 2, then blog.example with "
                    + "uniqueVisitors 1");

    static final String SPEC_WITH_OPEN_QUESTION = spec(List.of(AC1),
            List.of(Map.of("question", "How should a visitor be identified? IP addresses and user agents are "
                            + "personal data.",
                            "proposedDefault", "Store a SHA-256 of IP address and user agent with each click",
                            "confidence", "LOW"),
                    Map.of("question", "Unique per UTC day, or over the link's lifetime?",
                            "proposedDefault", "Per UTC day", "confidence", "MEDIUM")),
            List.of("Lifetime unique visitors"));

    static final String SPEC_ANSWERED = spec(List.of(AC1, AC2),
            List.of(Map.of("question", "Unique per UTC day, or over the link's lifetime?",
                    "proposedDefault", "Per UTC day (answered by the product owner)", "confidence", "HIGH")),
            List.of("Lifetime unique visitors", "Visitors per referrer"));

    static final String SPEC_WITH_REFERRERS = spec(List.of(AC1, AC2, AC3),
            List.of(Map.of("question", "Unique per UTC day, or over the link's lifetime?",
                    "proposedDefault", "Per UTC day (answered by the product owner)", "confidence", "HIGH")),
            List.of("Lifetime unique visitors"));

    private static String spec(List<Map<String, Object>> criteria, List<Map<String, Object>> ambiguities,
                               List<String> outOfScope) {
        Map<String, Object> s = new HashMap<>();
        s.put("title", "Unique visitors in link statistics");
        s.put("summary", "Link statistics show how many distinct visitors a link had, next to clicks. "
                + "Visitors are counted without keeping personal data.");
        s.put("goals", List.of("Owners see unique visitors per day"));
        s.put("nonFunctional", List.of("No extra latency on redirects: counting stays off the redirect path",
                "Existing statistics fields unchanged"));
        s.put("acceptanceCriteria", criteria);
        s.put("ambiguities", ambiguities);
        s.put("assumptions", List.of("Clicks recorded before this change have no visitor information"));
        s.put("outOfScope", outOfScope);
        return json(s);
    }

    static String plan(boolean withReferrers) {
        List<String> all = withReferrers ? List.of("AC1", "AC2", "AC3") : List.of("AC1", "AC2");
        List<Map<String, Object>> nodes = new ArrayList<>(List.of(
                node("design", "DESIGN", "ADR: visitor identity, daily salt, schema and API fields; reviewed with "
                        + "product before building", List.of(), "MEDIUM", true, all),
                node("add-visitor-hash", "SCHEMA_MIGRATION", "V3: nullable visitor_hash column on click_events",
                        List.of("design"), "HIGH", true, List.of("AC2")),
                node("unique-visitors", "IMPLEMENT", "Packages clicks, redirect, stats and the redirect controller: "
                        + "hash on redirect, count distinct hashes in stats", List.of("design"), "MEDIUM", false, all),
                node("visitor-tests", "TEST", "API and database tests for AC1 and AC2", List.of("design"), "LOW", false,
                        List.of("AC1", "AC2"))));
        List<String> joined = new ArrayList<>(List.of("add-visitor-hash", "unique-visitors", "visitor-tests"));
        if (withReferrers) {
            nodes.add(node("referrer-visitor-tests", "TEST", "API test for AC3", List.of("design"), "LOW", false,
                    List.of("AC3")));
            joined.add("referrer-visitor-tests");
        }
        nodes.add(node("integrate", "JOIN", "Merge and run the full suite", joined, "MEDIUM", false, List.of()));
        nodes.add(node("docs", "DOCS", "README statistics section", List.of("integrate"), "LOW", false, List.of()));
        nodes.add(node("security-scan", "SECURITY_SCAN", "Scan the merged change", List.of("integrate"), "LOW",
                false, List.of()));
        nodes.add(node("release-readiness", "RELEASE_READINESS", "Evidence and release approval",
                List.of("docs", "security-scan"), "HIGH", true, List.of()));
        return json(Map.of("rationale", (withReferrers ? "Re-plan after the design review added per-referrer "
                        + "visitors (AC3): a separate test task for it; the implementation task now covers AC3. "
                        : "") + "The schema change, the implementation and the tests run in parallel after a "
                        + "design that product reviews first, because visitor identity is a privacy decision.",
                "nodes", nodes));
    }

    static String design(boolean withReferrers) {
        String adr = """
                # 1. Unique visitors without personal data

                Status: accepted

                ## Decision
                - A visitor is SHA-256(salt of the UTC day, IP address, user agent). The salt is 32 random bytes held
                  in memory by `com.agentic.shortener.clicks.VisitorHasher` and replaced when the day changes; it is
                  never stored. Neither the IP address nor the user agent is stored.
                - Migration V3 adds a nullable `visitor_hash CHAR(64)` column to `click_events`. Older clicks have none.
                - `RedirectService` computes the hash on each redirect from the client IP and `User-Agent`.
                - API: each `clicksPerDay[]` entry gains `uniqueVisitors` (distinct hashes that day).
                %s
                ## Consequences
                Hashes cannot be linked across days. With several nodes each has its own salt, so one person can
                count once per node; a shared daily salt in Redis would fix that.
                """.formatted(withReferrers ? "- API: each `topReferrers[]` entry gains `uniqueVisitors` (distinct "
                + "hashes from that referrer).\n" : "");
        return json(Map.of("summary", "ADR for unique visitors" + (withReferrers ? ", including per referrer" : ""),
                "edits", List.of(Map.of("path", "docs/decisions/0001-unique-visitors.md", "action", "create",
                        "content", adr)),
                "decisions", List.of(Map.of("title", "Visitor identity", "context", "AC2 and the product owner's answer",
                        "options", List.of("plain hash of IP and user agent", "hash with a stored key",
                                "hash with a per-day salt kept in memory"),
                        "choice", "hash with a per-day salt kept in memory",
                        "rationale", "Counts distinct visitors per day without anything that identifies them later"))));
    }

    /** Create edits for new files, replace edits for files the shortener already has. */
    private static List<Map<String, String>> files(String... paths) {
        List<Map<String, String>> out = new ArrayList<>();
        for (String p : paths) {
            String content = read(FILES.resolve(p));
            out.add(Files.exists(SHORTENER.resolve(p))
                    ? Map.of("path", p, "action", "replace", "content", content)
                    : Map.of("path", p, "action", "create", "content", content));
        }
        return out;
    }

    private static String edits(String summary, List<Map<String, String>> edits) {
        return json(Map.of("summary", summary, "edits", edits, "decisions", List.of()));
    }

    private static Map<String, Object> node(String id, String type, String description, List<String> dependsOn,
                                            String risk, boolean approval, List<String> criteria) {
        return Map.of("id", id, "type", type, "description", description, "dependsOn", dependsOn, "risk", risk,
                "requiresApproval", approval, "acceptanceCriteria", criteria);
    }

    private static String json(Object value) {
        return PlanningJson.write(value);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
