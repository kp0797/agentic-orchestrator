package com.agentic.orchestrator.workspace;

import com.agentic.orchestrator.gates.WorkspaceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceTest {
    @TempDir
    Path tmp;
    private RunWorkspace ws;
    private final PathPermissions coder = new PathPermissions(List.of("src/main/**", "pom.xml"),
            List.of("policies/**", ".github/**"));

    @BeforeEach
    void setUp() throws Exception {
        Path template = tmp.resolve("template");
        Files.createDirectories(template.resolve("src/main"));
        Files.writeString(template.resolve("pom.xml"), "<project/>\n");
        Files.writeString(template.resolve("src/main/App.java"), "class App { int v = 1; }\n");
        Files.createDirectories(template.resolve("target"));
        Files.writeString(template.resolve("target/junk.class"), "x");
        Files.write(template.resolve(".DS_Store"), new byte[]{0, 0, 0, 1, (byte) 0xff});
        Files.write(template.resolve("src/main/._App.java"), new byte[]{0, 5, 22, 7});
        ws = RunWorkspace.create(tmp.resolve("run"), template);
    }

    @Test
    void templateIsCommittedWithoutBuildOutputOrOsMetadata() {
        assertThat(ws.listFiles(ws.repo())).containsExactlyInAnyOrder("pom.xml", "src/main/App.java");
    }

    @Test
    void appliesEditsAndDescribesTheCommittedChange() {
        RunWorkspace.Worktree wt = ws.prepare("impl", List.of());
        EditApplier.apply(wt.directory(), List.of(
                FileEdit.patch("src/main/App.java", "int v = 1;", "int v = 2;"),
                FileEdit.create("src/main/Util.java", "class Util {}\n")), coder);
        WorkspaceRef ref = ws.commit(wt, "impl change");

        assertThat(ref.changed()).isTrue();
        assertThat(ref.changedFiles()).containsExactlyInAnyOrder("src/main/App.java", "src/main/Util.java");
        assertThat(ref.diff()).contains("-class App { int v = 1; }").contains("+class App { int v = 2; }");
        assertThat(ref.addedLines()).isEqualTo(2);
        assertThat(ref.removedLines()).isEqualTo(1);
    }

    @Test
    void refusesWritesOutsidePermissionsAndAppliesNothing() {
        RunWorkspace.Worktree wt = ws.prepare("impl", List.of());
        assertThatThrownBy(() -> EditApplier.apply(wt.directory(), List.of(
                FileEdit.create("src/main/Ok.java", "class Ok {}"),
                FileEdit.create("policies/policy.yaml", "maxDiffLines: 999999"),
                FileEdit.create("../escape.txt", "x"),
                FileEdit.create("src/test/Sneaky.java", "class Sneaky {}")), coder))
                .isInstanceOfSatisfying(EditApplier.EditRejectedException.class, e -> {
                    assertThat(e.permissionDenied()).isTrue();
                    assertThat(e.problems()).anyMatch(p -> p.contains("protected"))
                            .anyMatch(p -> p.contains("escapes the workspace"))
                            .anyMatch(p -> p.contains("outside this agent's writable paths"));
                });
        assertThat(wt.directory().resolve("src/main/Ok.java")).doesNotExist(); // all or nothing
    }

    @Test
    void rejectsAmbiguousPatchesAndWrongActions() {
        RunWorkspace.Worktree wt = ws.prepare("impl", List.of());
        assertThatThrownBy(() -> EditApplier.apply(wt.directory(), List.of(
                FileEdit.patch("src/main/App.java", "missing text", "x"),
                FileEdit.create("src/main/App.java", "dup"),
                new FileEdit("src/main/X.java", "rewrite", "x", null, null)), coder))
                .isInstanceOfSatisfying(EditApplier.EditRejectedException.class, e -> assertThat(e.problems())
                        .anyMatch(p -> p.contains("found 0 times"))
                        .anyMatch(p -> p.contains("already exists"))
                        .anyMatch(p -> p.contains("unknown action")));
    }

    @Test
    void nodeStartsFromTheMergeOfItsInputsAndConflictsAreReported() {
        RunWorkspace.Worktree a = ws.prepare("a", List.of());
        EditApplier.apply(a.directory(), List.of(FileEdit.create("src/main/A.java", "class A {}")), coder);
        String ca = ws.commit(a, "a").headCommit();
        RunWorkspace.Worktree b = ws.prepare("b", List.of());
        EditApplier.apply(b.directory(), List.of(FileEdit.create("src/main/B.java", "class B {}")), coder);
        String cb = ws.commit(b, "b").headCommit();

        RunWorkspace.Worktree join = ws.prepare("join", List.of(ca, cb));
        assertThat(join.directory().resolve("src/main/A.java")).exists();
        assertThat(join.directory().resolve("src/main/B.java")).exists();

        RunWorkspace.Worktree c = ws.prepare("c", List.of());
        EditApplier.apply(c.directory(), List.of(FileEdit.patch("src/main/App.java", "int v = 1;", "int v = 3;")), coder);
        String cc = ws.commit(c, "c").headCommit();
        RunWorkspace.Worktree d = ws.prepare("d", List.of());
        EditApplier.apply(d.directory(), List.of(FileEdit.patch("src/main/App.java", "int v = 1;", "int v = 4;")), coder);
        String cd = ws.commit(d, "d").headCommit();
        assertThatThrownBy(() -> ws.prepare("conflict", List.of(cc, cd)))
                .isInstanceOfSatisfying(RunWorkspace.MergeConflictException.class,
                        e -> assertThat(e.files()).containsExactly("src/main/App.java"));
    }

    @Test
    void compensationResetsTheBranchAndRetryStartsClean() {
        RunWorkspace.Worktree wt = ws.prepare("impl", List.of());
        EditApplier.apply(wt.directory(), List.of(FileEdit.create("src/main/Bad.java", "class Bad {}")), coder);
        ws.commit(wt, "bad");
        ws.resetToBase(wt);
        assertThat(wt.directory().resolve("src/main/Bad.java")).doesNotExist();

        EditApplier.apply(wt.directory(), List.of(FileEdit.create("src/main/Leftover.java", "x")), coder);
        RunWorkspace.Worktree again = ws.prepare("impl", List.of());
        assertThat(again.directory().resolve("src/main/Leftover.java")).doesNotExist();
    }

    /**
     * Run directories on a mounted volume (Docker Desktop on macOS) can be owned by another user than
     * the process; git then refuses them as "dubious ownership". Found in the first Docker run of the
     * brownfield scenario. Needs permission to change ownership (root, as in the Docker test container).
     */
    @Test
    void worksInARepositoryOwnedByAnotherUser() throws Exception {
        Process chown = new ProcessBuilder("chown", "-R", "65534", tmp.resolve("run").toString())
                .redirectErrorStream(true).start();
        org.junit.jupiter.api.Assumptions.assumeTrue(chown.waitFor() == 0, "cannot change file ownership here");

        RunWorkspace.Worktree wt = ws.prepare("other-owner", List.of());
        EditApplier.apply(wt.directory(), List.of(FileEdit.create("src/main/B.java", "class B {}\n")), coder);

        assertThat(ws.commit(wt, "works despite ownership").changedFiles()).containsExactly("src/main/B.java");
    }
}
