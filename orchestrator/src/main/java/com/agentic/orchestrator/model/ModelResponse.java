package com.agentic.orchestrator.model;

/**
 * @param truncated the model stopped because it hit the output token cap, so the answer is cut off
 */
public record ModelResponse(String text, String model, long inputTokens, long outputTokens, boolean truncated) {

    public ModelResponse(String text, String model, long inputTokens, long outputTokens) {
        this(text, model, inputTokens, outputTokens, false);
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
