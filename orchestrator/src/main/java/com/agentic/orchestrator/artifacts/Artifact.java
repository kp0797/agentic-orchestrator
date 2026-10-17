package com.agentic.orchestrator.artifacts;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Immutable, content-addressed output. Its id is the SHA-256 of its content. */
public record Artifact(String hash, String name, String mediaType, byte[] content, Lineage lineage) {
    public Artifact {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(lineage, "lineage");
        content = content.clone();
        if (!hash.equals(Hashing.sha256(content))) {
            throw new IllegalArgumentException("artifact hash does not match its content");
        }
    }

    public static Artifact of(ArtifactDraft draft, Lineage lineage) {
        byte[] bytes = draft.content();
        return new Artifact(Hashing.sha256(bytes), draft.name(), draft.mediaType(), bytes, lineage);
    }

    public String contentAsString() {
        return new String(content, StandardCharsets.UTF_8);
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
