package com.agentic.orchestrator.gates;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Gates that judge a committed change. All deterministic; none of them reads the agent's reasoning. */
public final class WorkspaceGates {
    private WorkspaceGates() {}

    static Optional<WorkspaceRef> require(GateContext ctx) {
        return ctx.workspace();
    }

    static List<String> matching(List<String> files, List<String> globs) {
        List<PathMatcher> matchers = globs.stream()
                .map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g)).toList();
        return files.stream().filter(f -> matchers.stream().anyMatch(m -> m.matches(Path.of(f)))).toList();
    }

    static List<String> addedLines(String diff) {
        List<String> out = new ArrayList<>();
        for (String line : diff.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                out.add(line.substring(1));
            }
        }
        return out;
    }

    /** Defence in depth: the tool layer already refuses these writes; merges must not sneak them in either. */
    public static final class ProtectedPaths implements Gate {
        public static final String ID = "protected-paths";
        private final List<String> globs;

        public ProtectedPaths(List<String> globs) {
            this.globs = List.copyOf(globs);
        }

        public String id() { return ID; }

        public GateResult evaluate(GateContext ctx) {
            Optional<WorkspaceRef> ws = require(ctx);
            if (ws.isEmpty()) return GateResult.fail(ID, "node produced no workspace change to check");
            List<String> hits = matching(ws.get().changedFiles(), globs);
            return hits.isEmpty() ? GateResult.pass(ID, "no protected path changed")
                    : GateResult.fail(ID, "protected paths changed: " + hits);
        }
    }

    /** Forces decomposition: one node, one reviewable change. */
    public static final class DiffSize implements Gate {
        public static final String ID = "diff-size";
        private final int maxLines;

        public DiffSize(int maxLines) {
            this.maxLines = maxLines;
        }

        public String id() { return ID; }

        public GateResult evaluate(GateContext ctx) {
            Optional<WorkspaceRef> ws = require(ctx);
            if (ws.isEmpty()) return GateResult.fail(ID, "node produced no workspace change to check");
            int total = ws.get().addedLines() + ws.get().removedLines();
            return total <= maxLines ? GateResult.pass(ID, total + " changed lines (limit " + maxLines + ")")
                    : GateResult.fail(ID, total + " changed lines exceeds the limit of " + maxLines
                    + "; split the change or simplify it");
        }
    }

    /** Requires a change: an implementation node that changed nothing has not done its job. */
    public static final class NonEmptyChange implements Gate {
        public static final String ID = "non-empty-change";

        public String id() { return ID; }

        public GateResult evaluate(GateContext ctx) {
            Optional<WorkspaceRef> ws = require(ctx);
            if (ws.isEmpty() || !ws.get().changed() || ws.get().changedFiles().isEmpty()) {
                return GateResult.fail(ID, "no files were changed");
            }
            return GateResult.pass(ID, ws.get().changedFiles().size() + " file(s) changed");
        }
    }

    /** Migrations, build files, security code: allowed, but a human must look. */
    public static final class ApprovalPaths implements Gate {
        public static final String ID = "approval-paths";
        private final List<String> globs;

        public ApprovalPaths(List<String> globs) {
            this.globs = List.copyOf(globs);
        }

        public String id() { return ID; }

        public GateResult evaluate(GateContext ctx) {
            Optional<WorkspaceRef> ws = require(ctx);
            if (ws.isEmpty()) return GateResult.pass(ID, "no workspace change");
            List<String> hits = matching(ws.get().changedFiles(), globs);
            return hits.isEmpty() ? GateResult.pass(ID, "no high-impact paths changed")
                    : GateResult.escalate(ID, "high-impact paths changed: " + hits,
                    "change touches " + hits + " (schema, build or security)");
        }
    }

    /** A new dependency needs a licence and supply-chain review by a person. */
    public static final class NewDependency implements Gate {
        public static final String ID = "new-dependency";
        private static final Pattern ARTIFACT = Pattern.compile("<artifactId>([^<]+)</artifactId>");
        private final List<String> licenseAllowlist;

        public NewDependency(List<String> licenseAllowlist) {
            this.licenseAllowlist = List.copyOf(licenseAllowlist);
        }

        public String id() { return ID; }

        public GateResult evaluate(GateContext ctx) {
            Optional<WorkspaceRef> ws = require(ctx);
            if (ws.isEmpty() || ws.get().changedFiles().stream().noneMatch(f -> f.endsWith("pom.xml"))) {
                return GateResult.pass(ID, "build file unchanged");
            }
            Set<String> added = new LinkedHashSet<>();
            for (String line : addedLines(ws.get().diff())) {
                Matcher m = ARTIFACT.matcher(line);
                if (m.find()) added.add(m.group(1));
            }
            if (added.isEmpty()) {
                return GateResult.escalate(ID, "build file changed without new artifacts", "pom.xml changed");
            }
            return GateResult.escalate(ID, "dependencies added: " + added,
                    "new dependencies " + added + " need review; licence must be one of " + licenseAllowlist);
        }
    }

    /** Disabling or deleting tests to get green is exactly what an agent under pressure might do. */
    public static final class DisabledTests implements Gate {
        public static final String ID = "disabled-tests";

        public String id() { return ID; }

        public GateResult evaluate(GateContext ctx) {
            Optional<WorkspaceRef> ws = require(ctx);
            if (ws.isEmpty()) return GateResult.pass(ID, "no workspace change");
            List<String> reasons = new ArrayList<>();
            if (addedLines(ws.get().diff()).stream().anyMatch(l -> l.contains("@Disabled") || l.contains("@Ignore"))) {
                reasons.add("tests were disabled");
            }
            for (String block : ws.get().diff().split("diff --git ")) {
                // A deleted file's block has '+++ /dev/null'; only test sources matter here.
                if (block.contains("+++ /dev/null") && block.contains("src/test/")) {
                    reasons.add("a test file was deleted");
                    break;
                }
            }
            return reasons.isEmpty() ? GateResult.pass(ID, "no tests disabled or deleted")
                    : GateResult.escalate(ID, String.join(", ", reasons), String.join(", ", reasons));
        }
    }

    /** Secret patterns on lines the change adds. */
    public static final class SecretsInDiff implements Gate {
        public static final String ID = "secrets-in-diff";
        private final NoSecretsGate patterns = new NoSecretsGate();

        public String id() { return ID; }

        public GateResult evaluate(GateContext ctx) {
            Optional<WorkspaceRef> ws = require(ctx);
            if (ws.isEmpty()) return GateResult.pass(ID, "no workspace change");
            String added = String.join("\n", addedLines(ws.get().diff()));
            var asOutput = com.agentic.orchestrator.artifacts.ArtifactDraft.text("diff", added);
            GateResult r = patterns.evaluate(new GateContext(ctx.runId(), ctx.node(), ctx.attempt(), List.of(asOutput),
                    Optional.empty()));
            return r.passed() ? GateResult.pass(ID, "no secret patterns in added lines")
                    : GateResult.fail(ID, r.detail().replace("'diff'", "the change"));
        }
    }

    /**
     * Flyway migrations in the baseline have already run in production: changing or deleting one makes
     * every existing database disagree with the code. Schema changes must be a new version.
     */
    public static final class MigrationsImmutable implements Gate {
        public static final String ID = "migrations-immutable";
        static final List<String> GLOBS = List.of("**/db/migration/**");

        public String id() { return ID; }

        public GateResult evaluate(GateContext ctx) {
            Optional<WorkspaceRef> ws = require(ctx);
            if (ws.isEmpty()) return GateResult.fail(ID, "node produced no workspace change to check");
            List<String> touched = matching(ws.get().changedFiles(), GLOBS);
            if (touched.isEmpty()) {
                return GateResult.pass(ID, "no migration changed");
            }
            Path dir = ws.get().directory();
            String root = Git.rootCommit(dir);
            List<String> applied = touched.stream()
                    .filter(f -> Git.run(dir, "cat-file", "-e", root + ":" + f).ok()).toList();
            return applied.isEmpty() ? GateResult.pass(ID, "only new migrations added: " + touched)
                    : GateResult.fail(ID, "applied migrations were changed or deleted: " + applied
                    + ". They have already run in production; revert them and add a new migration version instead.");
        }
    }
}
