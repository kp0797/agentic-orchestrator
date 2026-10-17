package com.agentic.orchestrator.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanTest {

    private static TaskNode n(String id, String... deps) {
        return TaskNode.builder(id, NodeType.IMPLEMENT).executor("fake").dependsOn(deps).build();
    }

    @Test
    void ordersNodesTopologically() {
        Plan plan = new Plan("p", 1, List.of(n("c", "a", "b"), n("a"), n("b", "a")));
        assertThat(plan.topologicalOrder()).containsExactly("a", "b", "c");
    }

    @Test
    void rejectsCycleAndNamesTheNodesInIt() {
        assertThatThrownBy(() -> new Plan("p", 1, List.of(n("a", "c"), n("b", "a"), n("c", "b"), n("d"))))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("cycle")
                .hasMessageContaining("[a, b, c]");
    }

    @Test
    void reportsEveryStructuralErrorAtOnce() {
        assertThatThrownBy(() -> new Plan("p", 1, List.of(n("a", "a"), n("a"), n("b", "ghost"))))
                .isInstanceOfSatisfying(PlanValidationException.class, e -> assertThat(e.errors())
                        .anyMatch(m -> m.contains("duplicate node id 'a'"))
                        .anyMatch(m -> m.contains("depends on itself"))
                        .anyMatch(m -> m.contains("unknown node 'ghost'")));
    }

    @Test
    void rejectsEmptyPlanAndBadIds() {
        assertThatThrownBy(() -> new Plan("p", 1, List.of())).hasMessageContaining("no nodes");
        assertThatThrownBy(() -> n("Bad Id")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void downstreamIncludesNodeAndAllDescendantsInOrder() {
        Plan plan = new Plan("p", 1, List.of(n("a"), n("b", "a"), n("c", "a"), n("d", "b", "c"), n("e")));
        assertThat(plan.downstreamInclusive("b")).containsExactly("b", "d");
        assertThat(plan.downstreamInclusive("a")).containsExactly("a", "b", "c", "d");
        assertThat(plan.downstreamInclusive("e")).containsExactly("e");
    }

    @Test
    void digestChangesWhenStructureChanges() {
        Plan one = new Plan("p", 1, List.of(n("a"), n("b", "a")));
        Plan same = new Plan("p", 1, List.of(n("a"), n("b", "a")));
        Plan other = new Plan("p", 1, List.of(n("a"), n("b")));
        assertThat(one.digest()).isEqualTo(same.digest()).isNotEqualTo(other.digest());
    }

    @Test
    void executorChainAndAttemptMapping() {
        TaskNode node = TaskNode.builder("x", NodeType.IMPLEMENT).executor("a").fallbacks("b", "c")
                .retryPolicy(RetryPolicy.attempts(2)).build();
        assertThat(node.maxTotalAttempts()).isEqualTo(6);
        assertThat(List.of(1, 2, 3, 4, 5, 6).stream().map(node::executorIndexForAttempt))
                .containsExactly(0, 0, 1, 1, 2, 2);
        assertThat(node.attemptWithinExecutor(3)).isEqualTo(1);
    }
}
