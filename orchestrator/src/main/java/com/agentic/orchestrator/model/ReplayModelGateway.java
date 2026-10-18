package com.agentic.orchestrator.model;

/**
 * Returns recorded responses. A request that was never recorded fails loudly. A replayed answer costs
 * nothing and uses no tokens, so it reports zero: run metrics and budgets show what this run consumed,
 * not what the recording did (that stays in the cassette).
 */
public final class ReplayModelGateway implements ModelGateway {
    private final Cassette cassette;

    public ReplayModelGateway(Cassette cassette) {
        this.cassette = cassette;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        Cassette.Entry e = cassette.next(request.key()).orElseThrow(() -> new CassetteMissException(request));
        return new ModelResponse(e.response(), e.model(), 0, 0, "max_tokens".equals(e.stopReason()));
    }
}
