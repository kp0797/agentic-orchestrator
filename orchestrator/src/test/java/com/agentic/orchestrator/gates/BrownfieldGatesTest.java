package com.agentic.orchestrator.gates;

import com.agentic.orchestrator.artifacts.ArtifactDraft;
import com.agentic.orchestrator.domain.NodeType;
import com.agentic.orchestrator.domain.TaskNode;
import com.agentic.orchestrator.engine.FaultSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The gates that protect existing code: applied migrations stay untouched, regression tests prove the bug. */
class BrownfieldGatesTest {
    @TempDir
    Path repo;

    private static final String BUGGY = """
            package x;
            public class Adder {
                public int add(int a, int b) { return a - b; }
            }
            """;
    private static final String FIXED = BUGGY.replace("a - b", "a + b");

    @Test
    void changingAnAppliedMigrationFailsAndAddingANewOnePasses() throws Exception {
        write("src/main/resources/db/migration/V1__init.sql", "CREATE TABLE t (id INT);");
        commit("baseline");

        write("src/main/resources/db/migration/V2__more.sql", "ALTER TABLE t ADD c INT;");
        assertThat(migrations("src/main/resources/db/migration/V2__more.sql").passed()).isTrue();

        write("src/main/resources/db/migration/V1__init.sql", "CREATE TABLE t (id INT, c INT);");
        GateResult edited = migrations("src/main/resources/db/migration/V1__init.sql",
                "src/main/resources/db/migration/V2__more.sql");
        assertThat(edited.passed()).isFalse();
        assertThat(edited.detail()).contains("V1__init.sql").doesNotContain("V2__more").contains("new migration version");
    }

    @Test
    void regressionTestsMustFailOnTheOriginalCode() throws Exception {
        baselineProject();
        write("src/main/java/x/Adder.java", FIXED);
        write("src/test/java/x/AdderTest.java", test("assertThat(new Adder().add(2, 3)).isEqualTo(5);"));
        commit("fix with regression test");

        GateResult r = regressionProof();

        assertThat(r.passed()).as(r.detail()).isTrue();
        assertThat(r.detail()).contains("fail on the original code").contains("AdderTest.adds:")
                .doesNotContain("[ERROR]");
    }

    @Test
    void aTestThatAlreadyPassesOnTheOriginalCodeProvesNothing() throws Exception {
        baselineProject();
        write("src/main/java/x/Adder.java", FIXED);
        write("src/test/java/x/AdderTest.java", test("assertThat(new Adder().add(0, 0)).isEqualTo(0);"));
        commit("fix with a weak test");

        GateResult r = regressionProof();

        assertThat(r.passed()).isFalse();
        assertThat(r.detail()).contains("pass on the original code");
    }

    @Test
    void testsThatOnlyCompileAgainstNewCodeCannotProveTheDefect() throws Exception {
        baselineProject();
        write("src/main/java/x/Adder.java", FIXED.replace("}\n}", "}\n    public int twice(int a) { return add(a, a); }\n}"));
        write("src/test/java/x/AdderTest.java", test("assertThat(new Adder().twice(2)).isEqualTo(4);"));
        commit("new API only");

        GateResult r = regressionProof();

        assertThat(r.passed()).isFalse();
        assertThat(r.detail()).contains("do not compile against the original code");
    }

    @Test
    void aFixWithoutAnyTestFails() throws Exception {
        baselineProject();
        write("src/main/java/x/Adder.java", FIXED);
        commit("fix only");

        assertThat(regressionProof().detail()).contains("no test classes were added or changed");
    }

    @Test
    void commandLineFaultsParse() {
        FaultSpec gate = FaultSpec.parse("fix-alias-race:gate:build@1,2");
        assertThat(gate.kind()).isEqualTo(FaultSpec.Kind.GATE_FAILURE);
        assertThat(gate.gateId()).isEqualTo("build");
        assertThat(gate.attempts()).isEqualTo(Set.of(1, 2));
        FaultSpec ex = FaultSpec.parse("design:exception");
        assertThat(ex.kind()).isEqualTo(FaultSpec.Kind.EXCEPTION);
        assertThat(ex.attempts()).containsExactly(1);
        assertThatThrownBy(() -> FaultSpec.parse("design:gate")).hasMessageContaining("node:gate:<gate-id>");
        assertThatThrownBy(() -> FaultSpec.parse("design:explode")).isInstanceOf(IllegalArgumentException.class);
    }

    private void baselineProject() throws Exception {
        Files.copy(Path.of("src/test/resources/e2e-template/pom.xml"), repo.resolve("pom.xml"));
        write("src/main/java/x/Adder.java", BUGGY);
        commit("baseline");
    }

    private static String test(String assertion) {
        return """
                package x;

                import org.junit.jupiter.api.Test;
                import static org.assertj.core.api.Assertions.assertThat;

                class AdderTest {
                    @Test
                    void adds() {
                        %s
                    }
                }
                """.formatted(assertion);
    }

    private GateResult regressionProof() {
        MavenGate.Settings maven = new MavenGate.Settings("mvn", true, System.getProperty("maven.repo.local"),
                Duration.ofMinutes(5));
        return new RegressionProof(maven).evaluate(context(List.of()));
    }

    private GateResult migrations(String... changed) {
        return new WorkspaceGates.MigrationsImmutable().evaluate(context(List.of(changed)));
    }

    private GateContext context(List<String> changed) {
        WorkspaceRef ref = new WorkspaceRef(repo, "node/x", "base", "head", changed, "", 1, 0);
        return new GateContext("r", TaskNode.builder("x", NodeType.JOIN).executor("integrate").build(), 1,
                List.of(ArtifactDraft.text("o", "x")), Optional.of(ref));
    }

    private void write(String rel, String content) throws Exception {
        Path p = repo.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private String commit(String message) {
        if (!Files.exists(repo.resolve(".git"))) {
            git("init", "-q", "-b", "main");
        }
        git("add", "-A");
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", message);
        return git("rev-parse", "HEAD").strip();
    }

    private String git(String... args) {
        Git.Result r = Git.run(repo, args);
        assertThat(r.ok()).as(r.output()).isTrue();
        return r.output();
    }
}
