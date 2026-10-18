package com.agentic.orchestrator.gates;

import com.agentic.orchestrator.artifacts.ArtifactDraft;
import com.agentic.orchestrator.domain.NodeType;
import com.agentic.orchestrator.domain.TaskNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceGatesTest {
    private static GateContext ctx(List<String> files, String diff, int added, int removed) {
        WorkspaceRef ref = new WorkspaceRef(Path.of("."), "node/x", "base", "head", files, diff, added, removed);
        return new GateContext("r", TaskNode.builder("x", NodeType.IMPLEMENT).executor("coder").build(), 1,
                List.of(ArtifactDraft.text("o", "x")), Optional.of(ref));
    }

    @Test
    void migrationsAndBuildFilesEscalateToApproval() {
        GateResult r = new WorkspaceGates.ApprovalPaths(List.of("**/db/migration/**", "pom.xml"))
                .evaluate(ctx(List.of("src/main/resources/db/migration/V2__expiry.sql", "src/main/A.java"), "", 3, 0));
        assertThat(r.passed()).isTrue();
        assertThat(r.escalates()).isTrue();
        assertThat(r.escalation()).contains("V2__expiry.sql");
    }

    @Test
    void newDependencyIsNamedForTheApprover() {
        String diff = "diff --git a/pom.xml b/pom.xml\n+++ b/pom.xml\n+        <dependency>\n"
                + "+            <artifactId>commons-lang3</artifactId>\n+        </dependency>\n";
        GateResult r = new WorkspaceGates.NewDependency(List.of("Apache-2.0")).evaluate(ctx(List.of("pom.xml"), diff, 3, 0));
        assertThat(r.escalates()).isTrue();
        assertThat(r.escalation()).contains("commons-lang3").contains("Apache-2.0");
    }

    @Test
    void disablingOrDeletingTestsEscalates() {
        String disabled = "diff --git a/src/test/A.java b/src/test/A.java\n+    @Disabled(\"flaky\")\n";
        assertThat(new WorkspaceGates.DisabledTests().evaluate(ctx(List.of("src/test/A.java"), disabled, 1, 0))
                .escalation()).contains("disabled");
        String deleted = "diff --git a/src/test/B.java b/src/test/B.java\ndeleted file mode 100644\n--- a/src/test/B.java\n+++ /dev/null\n";
        assertThat(new WorkspaceGates.DisabledTests().evaluate(ctx(List.of("src/test/B.java"), deleted, 0, 9))
                .escalation()).contains("deleted");
    }

    @Test
    void protectedPathsDiffSizeAndSecretsFail() {
        assertThat(new WorkspaceGates.ProtectedPaths(List.of("policies/**"))
                .evaluate(ctx(List.of("policies/policy.yaml"), "", 1, 1)).passed()).isFalse();
        assertThat(new WorkspaceGates.DiffSize(100).evaluate(ctx(List.of("a"), "", 90, 20)).detail())
                .contains("exceeds the limit of 100");
        assertThat(new WorkspaceGates.SecretsInDiff().evaluate(ctx(List.of("a"),
                "+String apiKey = \"sk-ant-abcdefghijklmnopqrstuvwxyz0123\";\n", 1, 0)).passed()).isFalse();
        // Fake, OpenAI-style: the check is provider-neutral.
        assertThat(new WorkspaceGates.SecretsInDiff().evaluate(ctx(List.of("a"),
                "+key: sk-proj-abcdefghijklmnopqrstuvwxyz0123456789ABCD\n", 1, 0)).passed()).isFalse();
        assertThat(new WorkspaceGates.SecretsInDiff().evaluate(ctx(List.of("a"),
                "+// see the docs for task-specific settings\n", 1, 0)).passed()).isTrue();
        assertThat(new WorkspaceGates.SecretsInDiff().evaluate(ctx(List.of("a"),
                "+String id = \"task-0123456789abcdef0123456789abcdef0123\";\n", 1, 0)).passed()).isTrue();
        assertThat(new WorkspaceGates.NonEmptyChange().evaluate(ctx(List.of(), "", 0, 0)).passed()).isFalse();
    }

    @Test
    void mavenOutputIsTrimmedToTheLinesThatExplainTheFailure() {
        String out = """
                [INFO] Downloading something
                [ERROR] /w/src/main/java/A.java:[3,5] cannot find symbol
                [ERROR]   symbol: class LinkRepo
                [ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
                [ERROR] -> [Help 1]
                Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
                expected: "0" but was: "a"
                """;
        String trimmed = MavenGate.trim(out);
        assertThat(trimmed).contains("cannot find symbol", "LinkRepo", "Tests run: 2", "expected: \"0\"")
                .doesNotContain("Downloading", "full stack trace", "[Help 1]");
    }

    @Test
    void securityScanFlagsHighRiskPatterns(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path src = dir.resolve("src/main/java");
        java.nio.file.Files.createDirectories(src);
        java.nio.file.Files.writeString(src.resolve("Bad.java"), """
                class Bad {
                    String password = "hunter2hunter2";
                    void q(String id) { em.createNativeQuery("select * from links where id=" + id); }
                    void run() throws Exception { Runtime.getRuntime().exec("ls"); }
                }
                """);
        List<String> findings = SecurityScan.scan(dir);
        assertThat(findings).anyMatch(f -> f.startsWith("HIGH|hardcoded-secret|"))
                .anyMatch(f -> f.startsWith("HIGH|sql-concatenation|"))
                .anyMatch(f -> f.startsWith("HIGH|command-exec|src/main/java/Bad.java:4"));
        GateResult r = new SecurityScan.Gate().evaluate(new GateContext("r",
                TaskNode.builder("s", NodeType.SECURITY_SCAN).executor("x").build(), 1,
                List.of(ArtifactDraft.text("security-report.txt", String.join("\n", findings))), Optional.empty()));
        assertThat(r.passed()).isFalse();
    }
}
