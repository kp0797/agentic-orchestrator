package com.agentic.orchestrator.demo;

import com.agentic.orchestrator.domain.NodeType;
import com.agentic.orchestrator.domain.Plan;
import com.agentic.orchestrator.domain.RetryPolicy;
import com.agentic.orchestrator.domain.RiskLevel;
import com.agentic.orchestrator.domain.TaskNode;
import com.agentic.orchestrator.gates.NoSecretsGate;
import com.agentic.orchestrator.gates.OutputsPresentGate;

import java.time.Duration;
import java.util.List;

/** The 8-node greenfield shape used by the Day-1 engine demo and tests (fake agents only). */
public final class DemoPlans {
    private DemoPlans() {}

    public static final RetryPolicy FAST_RETRY = new RetryPolicy(3, Duration.ofMillis(20), 2.0, Duration.ofMillis(200));

    public static Plan greenfield() {
        String[] gates = {OutputsPresentGate.ID, NoSecretsGate.ID};
        return new Plan("greenfield-fake", 1, List.of(
                node("requirements", NodeType.REQUIREMENTS, "Normalize the requirement into a spec")
                        .executor("fake").exitGates(gates).acceptanceCriteria("AC1", "AC2", "AC3").build(),
                node("design", NodeType.DESIGN, "OpenAPI, schema, interfaces, ADRs")
                        .dependsOn("requirements").executor("fake").exitGates(gates)
                        .requiresApproval(true).build(),
                node("implement", NodeType.IMPLEMENT, "Implement against the interfaces")
                        .dependsOn("design").executor("fake").fallbacks("fake-strong").exitGates(gates)
                        .acceptanceCriteria("AC1", "AC2").build(),
                node("write-tests", NodeType.TEST, "Tests from acceptance criteria, without seeing the code")
                        .dependsOn("design").executor("fake").exitGates(gates)
                        .acceptanceCriteria("AC1", "AC2", "AC3").build(),
                node("integrate", NodeType.JOIN, "Merge branches and run the full suite")
                        .dependsOn("implement", "write-tests").executor("fake").fallbacks("fake-strong")
                        .exitGates(gates).build(),
                node("docs", NodeType.DOCS, "README, runbook, API docs")
                        .dependsOn("integrate").executor("fake").exitGates(gates).build(),
                node("security-scan", NodeType.SECURITY_SCAN, "Static analysis and secret scan")
                        .dependsOn("integrate").executor("fake").exitGates(gates).build(),
                node("release-readiness", NodeType.RELEASE_READINESS, "Checklist and PR")
                        .dependsOn("docs", "security-scan").executor("fake").exitGates(gates)
                        .risk(RiskLevel.HIGH).build()));
    }

    private static TaskNode.Builder node(String id, NodeType type, String description) {
        return TaskNode.builder(id, type).description(description).retryPolicy(FAST_RETRY);
    }
}
