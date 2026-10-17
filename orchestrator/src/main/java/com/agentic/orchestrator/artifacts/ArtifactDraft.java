package com.agentic.orchestrator.artifacts;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Output proposed by an executor. It becomes an {@link Artifact} once the engine stores it. */
public record ArtifactDraft(String name, String mediaType, byte[] content, String model, String promptHash) {
    public ArtifactDraft {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(content, "content");
        mediaType = mediaType == null ? "text/plain" : mediaType;
        content = content.clone();
    }

    public static ArtifactDraft text(String name, String text) {
        return new ArtifactDraft(name, "text/plain", text.getBytes(StandardCharsets.UTF_8), null, null);
    }

    public String contentAsString() {
        return new String(content, StandardCharsets.UTF_8);
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
