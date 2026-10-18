package com.agentic.orchestrator.gates;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * For defect fixes: the tests added or changed in this run must FAIL on the original code. A
 * regression test that passes before the fix proves nothing. The gate checks out the baseline commit
 * in a scratch directory, adds the run's new and changed test files, and runs just those tests.
 *
 * <p>Pass: at least one of them fails on the baseline. Fail: they all pass there, they do not compile
 * against the original API, or there are none.
 */
public final class RegressionProof implements Gate {
    public static final String ID = "regression-proof";
    private final MavenGate.Settings maven;

    public RegressionProof(MavenGate.Settings maven) {
        this.maven = maven;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        Optional<WorkspaceRef> ws = ctx.workspace();
        if (ws.isEmpty()) {
            return GateResult.fail(ID, "no workspace to check");
        }
        Path dir = ws.get().directory();
        String root = Git.rootCommit(dir);
        List<String> testTree = Git.lines(Git.run(dir, "diff", "--name-only", "--diff-filter=AM", root, "HEAD",
                "--", "src/test"));
        List<String> testClasses = testTree.stream()
                .filter(f -> f.startsWith("src/test/java/") && f.matches(".*Tests?\\.java"))
                .map(f -> f.substring(f.lastIndexOf('/') + 1, f.length() - ".java".length()))
                .distinct().toList();
        if (testClasses.isEmpty()) {
            return GateResult.fail(ID, "no test classes were added or changed: a fix needs a regression test "
                    + "that fails on the original code");
        }
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("regression-proof-");
            Path tar = scratch.resolve("baseline.tar");
            Git.Result archive = Git.run(dir, "archive", "--format=tar", "-o", tar.toString(), root);
            if (!archive.ok()) {
                return GateResult.fail(ID, "cannot export the baseline: " + archive.output());
            }
            Path base = Files.createDirectory(scratch.resolve("baseline"));
            Process untar = new ProcessBuilder("tar", "-xf", tar.toString(), "-C", base.toString())
                    .redirectErrorStream(true).start();
            if (untar.waitFor() != 0) {
                return GateResult.fail(ID, "cannot unpack the baseline");
            }
            for (String f : testTree) {
                Path target = base.resolve(f);
                Files.createDirectories(target.getParent());
                Files.copy(dir.resolve(f), target, StandardCopyOption.REPLACE_EXISTING);
            }
            MavenGate.Run run = MavenGate.run(maven, base, List.of("test", "-Dtest=" + String.join(",", testClasses),
                    "-Dsurefire.failIfNoSpecifiedTests=false", "-Djacoco.skip=true"));
            if (run.error() != null) {
                return GateResult.fail(ID, run.error());
            }
            if (run.output().contains("COMPILATION ERROR")) {
                return GateResult.fail(ID, "the new tests " + testClasses + " do not compile against the original "
                        + "code, so they cannot show the defect. Write them against the existing API:\n"
                        + MavenGate.trim(run.output()));
            }
            if (run.exitCode() == 0) {
                return GateResult.fail(ID, "the new tests " + testClasses + " pass on the original code, so they do "
                        + "not reproduce the defect. Make them exercise the failure described in the requirement.");
            }
            return GateResult.pass(ID, "the new tests fail on the original code, as a regression test must:\n"
                    + MavenGate.trim(run.output()));
        } catch (IOException e) {
            return GateResult.fail(ID, "cannot prepare the baseline: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GateResult.fail(ID, "interrupted");
        } finally {
            delete(scratch);
        }
    }

    private static void delete(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // scratch space in the temp directory; leaving it behind is harmless
        }
    }
}
