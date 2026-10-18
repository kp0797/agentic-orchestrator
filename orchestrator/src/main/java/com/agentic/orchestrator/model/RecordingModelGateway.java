package com.agentic.orchestrator.model;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Calls the delegate (normally the live gateway) and records every exchange. With a file, each
 * exchange is appended as it happens, so a crash mid-run keeps everything recorded so far.
 */
public final class RecordingModelGateway implements ModelGateway {
    private final ModelGateway delegate;
    private final Cassette cassette;
    private final Optional<Path> file;

    public RecordingModelGateway(ModelGateway delegate, Cassette cassette) {
        this(delegate, cassette, null);
    }

    public RecordingModelGateway(ModelGateway delegate, Cassette cassette, Path file) {
        this.delegate = delegate;
        this.cassette = cassette;
        this.file = Optional.ofNullable(file);
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        ModelResponse r = delegate.complete(request);
        Cassette.Entry entry = new Cassette.Entry(request.key(), r.model(), request.promptHash(), r.text(),
                r.inputTokens(), r.outputTokens(), r.truncated() ? "max_tokens" : "end_turn");
        cassette.add(entry);
        file.ifPresent(f -> JsonlCassetteFile.append(entry, f));
        return r;
    }
}
