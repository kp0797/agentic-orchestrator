package com.agentic.orchestrator.model;

/** Calls the delegate (normally the live gateway) and records every exchange to the cassette. */
public final class RecordingModelGateway implements ModelGateway {
    private final ModelGateway delegate;
    private final Cassette cassette;

    public RecordingModelGateway(ModelGateway delegate, Cassette cassette) {
        this.delegate = delegate;
        this.cassette = cassette;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        ModelResponse r = delegate.complete(request);
        cassette.add(new Cassette.Entry(request.key(), r.model(), request.promptHash(), r.text(), r.inputTokens(),
                r.outputTokens()));
        return r;
    }
}
