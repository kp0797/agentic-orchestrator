package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.domain.RunBudget;
import com.agentic.orchestrator.engine.FaultSpec;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class ScenarioLoader {
    private ScenarioLoader() {}

    @SuppressWarnings("unchecked")
    public static Scenario load(Path scenarioDir) {
        Path file = scenarioDir.resolve("scenario.yaml");
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> y = (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            Map<String, Object> b = (Map<String, Object>) y.getOrDefault("budget", Map.of());
            RunBudget budget = new RunBudget(
                    Duration.ofMinutes(((Number) b.getOrDefault("maxWallMinutes", 60)).longValue()),
                    ((Number) b.getOrDefault("maxTokens", 2_000_000)).longValue(),
                    ((Number) b.getOrDefault("maxCostUsd", 25.0)).doubleValue());
            List<FaultSpec> faults = new ArrayList<>();
            for (Map<String, Object> f : (List<Map<String, Object>>) y.getOrDefault("faults", List.of())) {
                Set<Integer> attempts = new HashSet<>();
                ((List<Object>) f.getOrDefault("attempts", List.of(1))).forEach(a -> attempts.add(((Number) a).intValue()));
                faults.add(new FaultSpec(String.valueOf(f.get("node")),
                        FaultSpec.Kind.valueOf(String.valueOf(f.getOrDefault("kind", "EXCEPTION"))),
                        attempts, (String) f.get("gate"), String.valueOf(f.getOrDefault("message", "injected"))));
            }
            Path root = scenarioDir.getParent();
            return new Scenario(
                    String.valueOf(y.get("name")),
                    String.valueOf(y.getOrDefault("description", "")),
                    String.valueOf(y.get("requirement")).strip(),
                    root.resolve(String.valueOf(y.get("template"))).normalize(),
                    scenarioDir.resolve(String.valueOf(y.getOrDefault("cassette", "cassette.jsonl"))),
                    budget, faults);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    public static List<Scenario> loadAll(Path scenariosRoot) {
        try (Stream<Path> dirs = Files.list(scenariosRoot)) {
            return dirs.filter(d -> Files.exists(d.resolve("scenario.yaml"))).sorted().map(ScenarioLoader::load).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
