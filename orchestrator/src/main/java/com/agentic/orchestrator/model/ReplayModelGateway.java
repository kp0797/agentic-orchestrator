package com.agentic.orchestrator.model;

/** Returns recorded responses. A request that was never recorded fails loudly. */
public final class ReplayModelGateway implements ModelGateway {
    private final Cassette cassette;

    public ReplayModelGateway(Cassette cassette) {
        this.cassette = cassette;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        Cassette.Entry e = cassette.next(request.key()).orElseThrow(() -> new CassetteMissException(request));
        return new ModelResponse(e.response(), e.model(), e.inputTokens(), e.outputTokens());
    }
}
