package com.agentic.orchestrator.artifacts;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryArtifactStore implements ArtifactStore {
    private final Map<String, Artifact> byHash = new ConcurrentHashMap<>();

    @Override
    public Artifact put(Artifact artifact) {
        return byHash.computeIfAbsent(artifact.hash(), h -> artifact);
    }

    @Override
    public Optional<Artifact> get(String hash) {
        return Optional.ofNullable(byHash.get(hash));
    }
}
