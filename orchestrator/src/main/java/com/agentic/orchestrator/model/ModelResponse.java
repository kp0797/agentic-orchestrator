package com.agentic.orchestrator.model;

public record ModelResponse(String text, String model, long inputTokens, long outputTokens) {
    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
