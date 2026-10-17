package com.agentic.orchestrator.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NodeStatusProperties {

    @Property
    void everyStatusCanEventuallyReturnToPending(@ForAll NodeStatus start) {
        // No dead ends: from any status a node can be brought back into play (resume / re-plan).
        Set<NodeStatus> seen = new HashSet<>();
        List<NodeStatus> frontier = new ArrayList<>(List.of(start));
        while (!frontier.isEmpty()) {
            NodeStatus s = frontier.remove(0);
            if (seen.add(s)) {
                frontier.addAll(s.allowedNext());
            }
        }
        assertThat(seen).contains(NodeStatus.PENDING);
    }

    @Property
    void succeededIsOnlyReachedThroughVerificationOrApproval(@ForAll NodeStatus from) {
        if (from.canTransitionTo(NodeStatus.SUCCEEDED)) {
            assertThat(from).isIn(NodeStatus.VERIFYING, NodeStatus.AWAITING_APPROVAL);
        }
    }

    @Property
    void randomTopologicalOrderRespectsEveryDependency(@ForAll("dags") List<TaskNode> nodes) {
        Plan plan = new Plan("p", 1, nodes);
        List<String> order = plan.topologicalOrder();
        for (TaskNode n : nodes) {
            for (String dep : n.dependsOn()) {
                assertThat(order.indexOf(dep)).isLessThan(order.indexOf(n.id()));
            }
        }
        assertThat(order).hasSize(nodes.size());
    }

    @Property
    void backoffNeverExceedsCap(@ForAll @IntRange(min = 1, max = 10) int attempt) {
        RetryPolicy p = RetryPolicy.DEFAULT;
        assertThat(p.backoffBefore(attempt)).isLessThanOrEqualTo(p.maxBackoff());
    }

    @Provide
    Arbitrary<List<TaskNode>> dags() {
        // Edges only point from a later index to an earlier one, so every generated graph is acyclic.
        return Arbitraries.integers().between(1, 25).flatMap(size ->
                Arbitraries.longs().map(seed -> {
                    Random r = new Random(seed);
                    List<TaskNode> nodes = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        List<String> deps = new ArrayList<>();
                        for (int j = 0; j < i; j++) {
                            if (r.nextInt(4) == 0) deps.add("n" + j);
                        }
                        nodes.add(TaskNode.builder("n" + i, NodeType.IMPLEMENT).executor("fake")
                                .dependsOn(deps.toArray(String[]::new)).build());
                    }
                    java.util.Collections.shuffle(nodes, r);
                    return nodes;
                }));
    }
}
