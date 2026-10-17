package com.agentic.orchestrator.workspace;

import com.agentic.orchestrator.gates.WorkspaceRef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * One git repository per run, seeded from a template, with one worktree and branch per node.
 * A node starts from the merge of its dependencies' commits, so parallel nodes never see each
 * other's half-done work, and the join node is where their changes meet.
 *
 * <p>Git metadata operations are serialized per run; builds in different worktrees run in parallel.
 */
public final class RunWorkspace {
    private final Path root;
    private final Path repo;
    private final Path worktrees;
    private final String initialCommit;

    private RunWorkspace(Path root, Path repo, Path worktrees, String initialCommit) {
        this.root = root;
        this.repo = repo;
        this.worktrees = worktrees;
        this.initialCommit = initialCommit;
    }

    /** Creates the run repository from a template directory (or an existing project). */
    public static RunWorkspace create(Path root, Path template) {
        try {
            Path repo = root.resolve("repo");
            Path worktrees = root.resolve("worktrees");
            Files.createDirectories(repo);
            Files.createDirectories(worktrees);
            copyTree(template, repo);
            GitCli.run(repo, "init", "-q", "-b", "main");
            GitCli.run(repo, "add", "-A");
            GitCli.run(repo, "commit", "-q", "--allow-empty", "-m", "baseline from " + template.getFileName());
            String head = GitCli.run(repo, "rev-parse", "HEAD").strip();
            return new RunWorkspace(root, repo, worktrees, head);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create run workspace in " + root, e);
        }
    }

    public Path root() { return root; }
    public Path repo() { return repo; }
    public String initialCommit() { return initialCommit; }

    /** A node's working copy, reset to the merge of the given base commits. */
    public record Worktree(String nodeId, Path directory, String branch, String baseCommit) {}

    public synchronized Worktree prepare(String nodeId, List<String> baseCommits) {
        List<String> bases = baseCommits.isEmpty() ? List.of(initialCommit) : baseCommits;
        String branch = "node/" + nodeId;
        Path dir = worktrees.resolve(nodeId);
        if (!Files.exists(dir)) {
            GitCli.run(repo, "worktree", "add", "-q", "-B", branch, dir.toString(), bases.get(0));
        } else {
            GitCli.run(dir, "checkout", "-q", "-B", branch, bases.get(0));
            GitCli.run(dir, "reset", "-q", "--hard", bases.get(0));
            GitCli.run(dir, "clean", "-q", "-fdx");
        }
        if (bases.size() > 1) {
            List<String> args = new ArrayList<>(List.of("merge", "-q", "--no-ff", "--no-edit", "-m",
                    "merge inputs of " + nodeId));
            args.addAll(bases.subList(1, bases.size()));
            try {
                GitCli.run(dir, args.toArray(String[]::new));
            } catch (GitCli.GitException e) {
                String conflicts = GitCli.run(dir, "diff", "--name-only", "--diff-filter=U").strip();
                GitCli.run(dir, "merge", "--abort");
                throw new MergeConflictException(nodeId, Arrays.asList(conflicts.split("\n")));
            }
        }
        String base = GitCli.run(dir, "rev-parse", "HEAD").strip();
        return new Worktree(nodeId, dir, branch, base);
    }

    /** Commits whatever the node changed and describes the change for gates and approvers. */
    public synchronized WorkspaceRef commit(Worktree wt, String message) {
        GitCli.run(wt.directory(), "add", "-A");
        String staged = GitCli.run(wt.directory(), "diff", "--cached", "--name-only").strip();
        if (!staged.isEmpty()) {
            GitCli.run(wt.directory(), "commit", "-q", "-m", message);
        }
        return describe(wt);
    }

    public synchronized WorkspaceRef describe(Worktree wt) {
        String head = GitCli.run(wt.directory(), "rev-parse", "HEAD").strip();
        String names = GitCli.run(wt.directory(), "diff", "--name-only", wt.baseCommit(), head).strip();
        List<String> changed = names.isEmpty() ? List.of() : List.of(names.split("\n"));
        String diff = GitCli.run(wt.directory(), "diff", wt.baseCommit(), head);
        int added = 0;
        int removed = 0;
        for (String line : GitCli.run(wt.directory(), "diff", "--numstat", wt.baseCommit(), head).split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length >= 2 && !parts[0].equals("-")) {
                added += Integer.parseInt(parts[0]);
                removed += Integer.parseInt(parts[1]);
            }
        }
        return new WorkspaceRef(wt.directory(), wt.branch(), wt.baseCommit(), head, changed, diff, added, removed);
    }

    /** Compensation: the node's branch goes back to where it started. */
    public synchronized void resetToBase(Worktree wt) {
        GitCli.run(wt.directory(), "reset", "-q", "--hard", wt.baseCommit());
        GitCli.run(wt.directory(), "clean", "-q", "-fdx");
    }

    /** Read-only view used to build context bundles. */
    public List<String> listFiles(Path dir) {
        String out = GitCli.run(dir, "ls-files");
        return out.isBlank() ? List.of() : List.of(out.strip().split("\n"));
    }

    public static final class MergeConflictException extends RuntimeException {
        private final List<String> files;

        public MergeConflictException(String nodeId, List<String> files) {
            super("merge conflict while preparing '" + nodeId + "' in " + files);
            this.files = List.copyOf(files);
        }

        public List<String> files() { return files; }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            throw new IOException("template not found: " + from);
        }
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(from) && (name.equals("target") || name.equals(".git"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Text of files a context bundle may include; skips build output and binaries. */
    public static String readText(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }

    public static List<Path> walk(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("/target/") && !p.toString().contains("/.git"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
