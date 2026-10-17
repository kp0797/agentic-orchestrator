package com.agentic.orchestrator.model;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads and writes cassettes as JSON Lines, one exchange per line, so diffs stay reviewable. */
public final class JsonlCassetteFile {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonlCassetteFile() {}

    public static Cassette read(Path file) {
        Cassette c = new Cassette();
        if (!Files.exists(file)) {
            return c;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    c.add(JSON.readValue(line, Cassette.Entry.class));
                }
            }
            return c;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read cassette " + file, e);
        }
    }

    public static synchronized void append(Cassette.Entry entry, Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, JSON.writeValueAsString(entry) + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot append to cassette " + file, e);
        }
    }

    public static void write(Cassette cassette, Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (Cassette.Entry e : cassette.all()) {
                    w.write(JSON.writeValueAsString(e));
                    w.newLine();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write cassette " + file, e);
        }
    }
}
