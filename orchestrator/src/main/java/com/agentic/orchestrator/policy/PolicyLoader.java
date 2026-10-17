package com.agentic.orchestrator.policy;

import com.agentic.orchestrator.domain.NodeType;
import com.agentic.orchestrator.domain.RiskLevel;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the two policy files with a safe YAML constructor (no arbitrary object creation). */
public final class PolicyLoader {
    private PolicyLoader() {}

    public static PolicyConfig load(Path policyFile, Path autonomyFile) {
        try (InputStream p = Files.newInputStream(policyFile); InputStream a = Files.newInputStream(autonomyFile)) {
            return parse(p, a);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read policy files", e);
        }
    }

    public static PolicyConfig loadDirectory(Path dir) {
        return load(dir.resolve("policy.yaml"), dir.resolve("autonomy.yaml"));
    }

    @SuppressWarnings("unchecked")
    static PolicyConfig parse(InputStream policy, InputStream autonomy) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> p = (Map<String, Object>) yaml.load(policy);
        Map<String, Object> a = (Map<String, Object>) yaml.load(autonomy);

        Map<NodeType, RiskLevel> floors = new EnumMap<>(NodeType.class);
        ((Map<String, String>) p.getOrDefault("riskFloors", Map.of()))
                .forEach((k, v) -> floors.put(NodeType.valueOf(k), RiskLevel.valueOf(v)));

        Map<String, PolicyConfig.AgentAutonomy> agents = new LinkedHashMap<>();
        ((Map<String, Map<String, Object>>) a.getOrDefault("agents", Map.of())).forEach((name, cfg) ->
                agents.put(name, new PolicyConfig.AgentAutonomy(String.valueOf(cfg.get("level")),
                        strings(cfg.get("writable")))));

        return new PolicyConfig(
                floors,
                RiskLevel.valueOf(String.valueOf(p.getOrDefault("approvalAtOrAbove", "HIGH"))),
                strings(p.get("protectedPaths")),
                strings(p.get("approvalPaths")),
                ((Number) p.getOrDefault("maxDiffLines", 400)).intValue(),
                strings(p.get("deniedExecutors")),
                strings(p.get("licenseAllowlist")),
                agents);
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            list.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }
}
