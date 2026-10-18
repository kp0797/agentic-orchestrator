package com.agentic.orchestrator.codebase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scores the repository's types against the words of a requirement, then follows references to find
 * what a change to the best matches would ripple into. Deliberately simple and explainable: every
 * item in the report says why it is there.
 */
public final class ImpactAnalyzer {
    static final int MAX_SEEDS = 5;
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9]+");
    private static final Pattern CAMEL = Pattern.compile("[A-Z]?[a-z0-9]+|[A-Z]+(?![a-z])");
    private static final Set<String> STOP = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "are", "was", "were", "when", "then", "given",
            "should", "must", "into", "have", "has", "not", "but", "any", "all", "can", "its", "our", "their",
            "they", "them", "there", "which", "what", "who", "will", "would", "been", "being", "also", "only",
            "each", "same", "other", "more", "most", "some", "such", "than", "too", "very", "just", "get", "set",
            "new", "use", "used", "using", "add", "make", "one", "two", "does", "did", "after", "before", "under",
            "over", "between", "about", "class", "service", "java", "test", "tests", "user", "users", "request",
            "response", "api", "http", "handler", "controller", "repository", "exception", "config", "impl",
            "error", "data", "value", "default", "keep", "existing", "fix", "change", "unchanged", "behaviour",
            "behavior");

    private ImpactAnalyzer() {}

    public static ImpactReport analyze(RepoIndex index, String requirementText) {
        if (index.isEmpty()) {
            return new ImpactReport(true, 0, index.testTypes().size(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), 1, List.of());
        }
        Set<String> words = words(requirementText);

        record Scored(RepoIndex.TypeInfo type, double score, Set<String> matched) {}
        // Rare identifier words say more than common ones: "alias" in two types beats "link" in twelve.
        Map<String, Integer> df = new java.util.HashMap<>();
        for (RepoIndex.TypeInfo t : index.mainTypes()) {
            identifierWords(t).forEach(w -> df.merge(w, 1, Integer::sum));
        }
        int n = index.mainTypes().size();
        List<Scored> scored = new ArrayList<>();
        for (RepoIndex.TypeInfo t : index.mainTypes()) {
            Set<String> matched = new TreeSet<>();
            double score = 0;
            if (requirementText.contains(t.simpleName())) {
                score += 10;
                matched.add(t.simpleName());
            }
            for (String w : split(t.simpleName())) {
                if (words.contains(w) && matched.add(w)) {
                    score += 3 * idf(n, df.getOrDefault(w, 1));
                }
            }
            for (String table : t.tables()) {
                if (words.contains(stem(table)) || words.containsAll(split(table))) {
                    score += 2;
                    matched.add("table " + table);
                }
            }
            for (String m : t.methods()) {
                for (String w : split(m)) {
                    if (words.contains(w) && matched.add(w)) {
                        score += idf(n, df.getOrDefault(w, 1));
                    }
                }
            }
            if (score > 0) {
                scored.add(new Scored(t, score, matched));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(s -> s.type().fqn()));
        double top = scored.isEmpty() ? 0 : scored.get(0).score();
        List<Scored> seeds = scored.stream().filter(s -> s.score() >= Math.max(3, top / 2)).limit(MAX_SEEDS)
                .toList();

        Map<String, String> reasons = new LinkedHashMap<>();
        Set<String> seedNames = new LinkedHashSet<>();
        for (Scored s : seeds) {
            seedNames.add(s.type().simpleName());
            reasons.put(s.type().simpleName(), "matches the requirement (" + String.join(", ", s.matched()) + ")");
        }
        for (String seed : seedNames) {
            index.type(seed).ifPresent(t -> t.references().stream().filter(r -> index.type(r).isPresent())
                    .forEach(r -> reasons.putIfAbsent(r, "used by " + seed)));
        }
        Set<String> level1 = index.dependentsOf(seedNames);
        level1.forEach(d -> reasons.putIfAbsent(d, "uses " + firstReferenced(index, d, seedNames)));
        Set<String> level2 = index.dependentsOf(level1);
        level2.forEach(d -> reasons.putIfAbsent(d, "uses " + firstReferenced(index, d, level1) + " (indirect)"));

        List<ImpactReport.Item> impacted = new ArrayList<>();
        Set<String> endpoints = new LinkedHashSet<>();
        Set<String> tables = new TreeSet<>();
        for (Map.Entry<String, String> e : reasons.entrySet()) {
            RepoIndex.TypeInfo t = index.type(e.getKey()).orElseThrow();
            impacted.add(new ImpactReport.Item(t.fqn(), t.file(), e.getValue()));
            endpoints.addAll(t.endpoints());
            tables.addAll(t.tables());
        }
        List<String> prefixes = endpoints.stream().map(ImpactAnalyzer::staticPrefix).filter(p -> p.length() > 1).toList();
        List<ImpactReport.Item> tests = new ArrayList<>();
        for (RepoIndex.TypeInfo t : index.testTypes()) {
            Optional<String> ref = t.references().stream().filter(reasons::containsKey).sorted().findFirst();
            Optional<String> path = t.paths().stream().filter(p -> prefixes.stream().anyMatch(p::startsWith)).findFirst();
            if (ref.isPresent()) {
                tests.add(new ImpactReport.Item(t.fqn(), t.file(), "references " + ref.get()));
            } else if (path.isPresent()) {
                tests.add(new ImpactReport.Item(t.fqn(), t.file(), "calls " + path.get()));
            }
        }
        // Tests built on a covering test base class (for example an API test base) cover the same code.
        Set<String> covering = new java.util.HashSet<>();
        tests.forEach(i -> covering.add(i.type().substring(i.type().lastIndexOf('.') + 1)));
        for (RepoIndex.TypeInfo t : index.testTypes()) {
            if (!covering.contains(t.simpleName())) {
                t.references().stream().filter(covering::contains).sorted().findFirst().ifPresent(base ->
                        tests.add(new ImpactReport.Item(t.fqn(), t.file(), "built on " + base)));
            }
        }
        tests.sort(Comparator.comparing(ImpactReport.Item::type));
        List<String> migrations = index.migrations().stream()
                .map(m -> m.file().substring(m.file().lastIndexOf('/') + 1)).toList();
        List<String> risks = new ArrayList<>();
        boolean touchesTables = index.migrations().stream().anyMatch(m -> m.tables().stream().anyMatch(tables::contains));
        if (touchesTables) {
            risks.add("Code in scope reads or writes tables " + tables + " that hold production data: schema changes"
                    + " need a new migration that also handles rows already stored, and a human approval.");
        }
        if (!endpoints.isEmpty()) {
            risks.add("Public HTTP endpoints are in scope: keep their contracts backwards compatible.");
        }
        if (!index.unparsable().isEmpty()) {
            risks.add("Files the analyzer could not parse (not analyzed): " + index.unparsable());
        }
        return new ImpactReport(false, index.mainTypes().size(), index.testTypes().size(),
                seeds.stream().map(s -> new ImpactReport.Item(s.type().fqn(), s.type().file(),
                        "matched " + String.join(", ", s.matched()) + String.format(" (score %.1f)", s.score())))
                        .toList(),
                impacted, tests, List.copyOf(endpoints), List.copyOf(tables), migrations,
                index.nextMigrationVersion(), risks);
    }

    /** "POST /api/links/{code}/stats" becomes "/api/links/". */
    static String staticPrefix(String endpoint) {
        String path = endpoint.substring(endpoint.indexOf(' ') + 1);
        int brace = path.indexOf('{');
        return brace < 0 ? path : path.substring(0, brace);
    }

    private static Set<String> identifierWords(RepoIndex.TypeInfo t) {
        Set<String> out = new java.util.HashSet<>(split(t.simpleName()));
        t.methods().forEach(m -> out.addAll(split(m)));
        return out;
    }

    private static double idf(int types, int typesWithWord) {
        return Math.max(0.5, Math.log((double) types / typesWithWord));
    }

    private static String firstReferenced(RepoIndex index, String type, Set<String> targets) {
        return index.type(type).map(t -> t.references().stream().filter(targets::contains).sorted().findFirst()
                .orElse("?")).orElse("?");
    }

    static Set<String> words(String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            for (String part : split(m.group())) {
                if (part.length() >= 3 && !STOP.contains(part)) {
                    out.add(part);
                }
            }
        }
        return out;
    }

    /** "findByCode" becomes [find, by, code]; words are lower-cased and singular. */
    static List<String> split(String identifier) {
        List<String> out = new ArrayList<>();
        Matcher m = CAMEL.matcher(identifier);
        while (m.find()) {
            out.add(stem(m.group().toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    static String stem(String w) {
        String s = w.toLowerCase(Locale.ROOT);
        if (s.endsWith("ies") && s.length() > 4) return s.substring(0, s.length() - 3) + "y";
        if (s.endsWith("ses") && s.length() > 4) return s.substring(0, s.length() - 2);
        if (s.endsWith("s") && !s.endsWith("ss") && s.length() > 3) return s.substring(0, s.length() - 1);
        return s;
    }
}
