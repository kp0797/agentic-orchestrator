package com.agentic.orchestrator.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Builds the gateway for a mode. The live gateway is only constructed outside replay mode. */
public final class ModelGateways {
    private ModelGateways() {}

    public static ModelGateway create(GatewayMode mode, Path cassetteFile, Supplier<ModelGateway> live) {
        return switch (mode) {
            case REPLAY -> {
                if (!Files.exists(cassetteFile)) {
                    throw new IllegalStateException("no cassette at " + cassetteFile
                            + ": record this scenario first (mode=record, needs ANTHROPIC_API_KEY)");
                }
                yield new ReplayModelGateway(JsonlCassetteFile.read(cassetteFile));
            }
            case LIVE -> live.get();
            case RECORD -> {
                try {
                    Files.deleteIfExists(cassetteFile);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("cannot reset cassette " + cassetteFile, e);
                }
                yield new RecordingModelGateway(live.get(), new Cassette(), cassetteFile);
            }
        };
    }
}
