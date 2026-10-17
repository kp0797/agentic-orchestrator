package com.agentic.orchestrator.model;

/** Replay found no recording for a request. Replay never falls back to a live call. */
public class CassetteMissException extends RuntimeException {
    public CassetteMissException(ModelRequest request) {
        super("cassette miss: re-record this scenario (model " + request.model() + ", request key "
                + request.key().substring(0, 12) + "...). The prompt, model or parameters changed since recording.");
    }
}
