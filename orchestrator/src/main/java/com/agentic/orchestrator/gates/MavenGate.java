package com.agentic.orchestrator.gates;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Runs Maven in the node's worktree. On failure the output is trimmed to the lines that explain it
 * (compiler errors, failing tests), which is what the agent gets as feedback on its next attempt.
 */
public final class MavenGate implements Gate {
    public record Settings(String executable, boolean offline, String repoLocal, Duration timeout) {
        public static Settings fromEnvironment() {
            return new Settings(
                    System.getenv().getOrDefault("AGENTIC_MVN", "mvn"),
                    Boolean.parseBoolean(System.getenv().getOrDefault("AGENTIC_MVN_OFFLINE", "false")),
                    System.getProperty("maven.repo.local", System.getenv("AGENTIC_MVN_REPO")),
                    Duration.ofMinutes(5));
        }
    }

    public static final String BUILD = "build";
    public static final String TESTS = "tests";

    private final String id;
    private final List<String> goals;
    private final Settings settings;

    private MavenGate(String id, List<String> goals, Settings settings) {
        this.id = id;
        this.goals = List.copyOf(goals);
        this.settings = settings;
    }

    /** Compiles main and test sources. */
    public static MavenGate build(Settings s) {
        return new MavenGate(BUILD, List.of("test-compile"), s);
    }

    /** Runs the unit and integration tests that do not need Docker. */
    public static MavenGate tests(Settings s) {
        return new MavenGate(TESTS, List.of("test"), s);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        Optional<WorkspaceRef> ws = ctx.workspace();
        if (ws.isEmpty()) {
            return GateResult.fail(id, "no workspace to build");
        }
        Path dir = ws.get().directory();
        if (!Files.exists(dir.resolve("pom.xml"))) {
            return GateResult.fail(id, "no pom.xml in workspace");
        }
        Run run = run(settings, dir, goals);
        if (run.error() != null) {
            return GateResult.fail(id, run.error());
        }
        if (run.exitCode() == 0) {
            return GateResult.pass(id, "mvn " + String.join(" ", goals) + " succeeded");
        }
        return GateResult.fail(id, "mvn " + String.join(" ", goals) + " failed:\n" + trim(run.output()));
    }

    /** The outcome of one Maven invocation; {@code error} is set when Maven could not run or timed out. */
    public record Run(int exitCode, String output, String error) {}

    /** Runs Maven with the given goals and arguments in a directory. Used by other gates too. */
    public static Run run(Settings settings, Path dir, List<String> goalsAndArgs) {
        List<String> cmd = new ArrayList<>(List.of(settings.executable(), "-B", "-q", "-Dstyle.color=never"));
        if (settings.offline()) cmd.add("-o");
        if (settings.repoLocal() != null && !settings.repoLocal().isBlank()) {
            cmd.add("-Dmaven.repo.local=" + settings.repoLocal());
        }
        cmd.addAll(goalsAndArgs);
        try {
            Path log = Files.createTempFile("mvn-", ".log");
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            if (!p.waitFor(settings.timeout().toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Run(-1, "", "maven " + goalsAndArgs + " timed out after " + settings.timeout());
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            Files.deleteIfExists(log);
            return new Run(p.exitValue(), output, null);
        } catch (IOException e) {
            return new Run(-1, "", "cannot run maven: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Run(-1, "", "interrupted");
        }
    }

    /** Keeps compiler errors and test failures, drops download noise and stack frames. */
    public static String trim(String output) {
        List<String> keep = new ArrayList<>();
        for (String line : output.split("\n")) {
            String l = line.strip();
            boolean useful = l.startsWith("[ERROR]") && !l.contains("To see the full stack trace")
                    && !l.contains("Re-run Maven") && !l.contains("For more information") && !l.contains("[Help 1]")
                    && !l.equals("[ERROR]") && !l.contains("-> [Help")
                    || l.contains("expected:") || l.contains("but was:") || l.startsWith("Tests run:");
            if (useful && !keep.contains(l)) {
                keep.add(l.length() > 300 ? l.substring(0, 300) + "..." : l);
            }
            if (keep.size() >= 40) {
                keep.add("... (truncated)");
                break;
            }
        }
        if (keep.isEmpty()) {
            String tail = output.length() > 2000 ? output.substring(output.length() - 2000) : output;
            return tail.strip();
        }
        return String.join("\n", keep);
    }
}
