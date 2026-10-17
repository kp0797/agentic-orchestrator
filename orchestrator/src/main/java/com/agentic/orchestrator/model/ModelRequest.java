package com.agentic.orchestrator.model;

import com.agentic.orchestrator.artifacts.Hashing;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** One LLM call. {@link #key()} identifies it in a cassette: same model, prompt and params, same key. */
public record ModelRequest(String model, String system, String prompt, Map<String, String> params) {
    public ModelRequest {
        Objects.requireNonNull(model, "model");
        system = system == null ? "" : system;
        Objects.requireNonNull(prompt, "prompt");
        params = Map.copyOf(params == null ? Map.of() : params);
    }

    public String key() {
        return Hashing.sha256(model + "\u0000" + system + "\u0000" + prompt + "\u0000" + new TreeMap<>(params));
    }

    public String promptHash() {
        return Hashing.sha256(system + "\u0000" + prompt);
    }
}
