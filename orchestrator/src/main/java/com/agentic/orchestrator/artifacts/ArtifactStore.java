package com.agentic.orchestrator.artifacts;

import java.util.Optional;

/** Content-addressed artifact storage. Storing the same content twice is a no-op. */
public interface ArtifactStore {
    Artifact put(Artifact artifact);

    Optional<Artifact> get(String hash);

    default boolean contains(String hash) {
        return get(hash).isPresent();
    }
}
