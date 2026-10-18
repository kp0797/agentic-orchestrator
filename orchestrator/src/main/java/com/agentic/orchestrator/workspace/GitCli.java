package com.agentic.orchestrator.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Thin wrapper over the git CLI. Every call has a timeout and fails with git's own error text. */
public final class GitCli {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private GitCli() {}

    public static String run(Path dir, String... args) {
        // safe.directory: run repositories live on mounted volumes (Docker Desktop, CI), where their owner
        // can differ from the process user and git would refuse them as "dubious ownership". The
        // orchestrator only ever runs git in repositories it created itself.
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "safe.directory=*", "-c", "user.name=agentic-orchestrator",
                "-c", "user.email=orchestrator@agentic.local", "-c", "commit.gpgsign=false",
                "-c", "core.autocrlf=false"));
        cmd.addAll(List.of(args));
        return exec(dir, cmd);
    }

    static String exec(Path dir, List<String> cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        try {
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new GitException("git timed out: " + String.join(" ", cmd.subList(9, cmd.size())));
            }
            String text = new String(out, StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                throw new GitException("git " + String.join(" ", cmd.subList(9, cmd.size())) + " failed: " + text.strip());
            }
            return text;
        } catch (IOException e) {
            throw new GitException("cannot run git: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitException("interrupted while running git");
        }
    }

    public static final class GitException extends RuntimeException {
        public GitException(String message) {
            super(message);
        }
    }
}
