package com.agentic.orchestrator.model;

import java.util.function.Function;

/** Deterministic stand-in for tests and engine demos. Token counts are a rough chars/4 estimate. */
public final class FakeModelGateway implements ModelGateway {
    private final Function<ModelRequest, String> responder;

    public FakeModelGateway(Function<ModelRequest, String> responder) {
        this.responder = responder;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        String text = responder.apply(request);
        return new ModelResponse(text, request.model(), (request.system().length() + request.prompt().length()) / 4,
                text.length() / 4);
    }
}
