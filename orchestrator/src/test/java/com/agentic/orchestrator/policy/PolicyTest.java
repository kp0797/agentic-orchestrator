package com.agentic.orchestrator.policy;

import com.agentic.orchestrator.domain.NodeType;
import com.agentic.orchestrator.domain.RiskLevel;
import com.agentic.orchestrator.domain.TaskNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyTest {
    private final PolicyConfig repoPolicy = PolicyLoader.loadDirectory(Path.of("../policies"));

    @Test
    void repositoryPoliciesLoadAndProtectThemselves() {
        assertThat(repoPolicy.protectedPaths()).contains("policies/**", ".github/**");
        assertThat(repoPolicy.agent("tester").writable()).containsExactly("src/test/**");
        assertThat(repoPolicy.agent("requirements").level()).isEqualTo("READ_ONLY");
        assertThat(repoPolicy.agent("unknown-agent").writable()).isEmpty(); // least privilege by default
    }

    @Test
    void policyRaisesRiskButNeverLowersIt() {
        YamlPolicyEngine engine = new YamlPolicyEngine(repoPolicy);
        var migration = engine.evaluate(TaskNode.builder("m", NodeType.SCHEMA_MIGRATION).executor("coder")
                .risk(RiskLevel.LOW).build());
        assertThat(migration.effectiveRisk()).isEqualTo(RiskLevel.HIGH);
        assertThat(migration.approvalRequired()).isTrue();
        assertThat(migration.reason()).contains("raised from LOW");

        var critical = engine.evaluate(TaskNode.builder("d", NodeType.DOCS).executor("docs")
                .risk(RiskLevel.CRITICAL).build());
        assertThat(critical.effectiveRisk()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void deniedExecutorStopsTheRun() {
        String policy = "riskFloors: {}\napprovalAtOrAbove: HIGH\nprotectedPaths: []\napprovalPaths: []\n"
                + "maxDiffLines: 10\ndeniedExecutors: [coder]\nlicenseAllowlist: []\n";
        String autonomy = "agents: {}\n";
        PolicyConfig cfg = PolicyLoader.parse(stream(policy), stream(autonomy));
        var d = new YamlPolicyEngine(cfg).evaluate(TaskNode.builder("i", NodeType.IMPLEMENT).executor("coder").build());
        assertThat(d.allowed()).isFalse();
        assertThat(d.critical()).isTrue();
    }

    @Test
    void readOnlyAgentsCannotBeGivenWritablePaths() {
        assertThatThrownBy(() -> new PolicyConfig.AgentAutonomy("READ_ONLY", java.util.List.of("src/**")))
                .hasMessageContaining("cannot have writable paths");
    }

    private static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
