package com.agentic.orchestrator.gates;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Read-only git queries for gates (gates must not depend on the workspace layer that writes). */
final class Git {
    record Result(int exitCode, String output) {
        boolean ok() {
            return exitCode == 0;
        }
    }

    private Git() {}

    static Result run(Path dir, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "safe.directory=*", "-c", "core.quotepath=off"));
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, "git timed out");
            }
            return new Result(p.exitValue(), out);
        } catch (IOException e) {
            return new Result(-1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "interrupted");
        }
    }

    /** The run's baseline: the first commit, made from the scenario's starting code. */
    static String rootCommit(Path dir) {
        Result r = run(dir, "rev-list", "--max-parents=0", "HEAD");
        if (!r.ok()) {
            throw new IllegalStateException("cannot find the baseline commit: " + r.output());
        }
        String[] lines = r.output().strip().split("\n");
        return lines[lines.length - 1].strip();
    }

    static List<String> lines(Result r) {
        String s = r.output().strip();
        return s.isEmpty() ? List.of() : List.of(s.split("\n"));
    }
}
