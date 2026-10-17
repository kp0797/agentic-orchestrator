package com.agentic.orchestrator.planning;

import com.agentic.orchestrator.domain.NodeType;
import com.agentic.orchestrator.domain.Plan;
import com.agentic.orchestrator.domain.PlanValidationException;
import com.agentic.orchestrator.domain.RiskLevel;
import com.agentic.orchestrator.domain.TaskNode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The rules a proposed plan must meet before anything runs. The LLM decides how to decompose the
 * work; these rules decide whether that decomposition is safe to execute.
 */
public final class PlanValidator {
    public static final int MAX_NODES = 15;
    static final Set<NodeType> ALLOWED = EnumSet.of(NodeType.DESIGN, NodeType.IMPLEMENT, NodeType.TEST,
            NodeType.SCHEMA_MIGRATION, NodeType.JOIN, NodeType.DOCS, NodeType.SECURITY_SCAN,
            NodeType.RELEASE_READINESS);

    private PlanValidator() {}

    public static List<String> validate(PlanProposal proposal, RequirementSpec spec) {
        List<String> errors = new ArrayList<>();
        List<PlanProposal.ProposedNode> nodes = proposal.nodes();
        if (nodes.isEmpty() || nodes.size() > MAX_NODES) {
            errors.add("plan must have 1 to " + MAX_NODES + " nodes, has " + nodes.size());
            return errors;
        }
        for (PlanProposal.ProposedNode n : nodes) {
            NodeType t = type(n.type());
            if (t == null || !ALLOWED.contains(t)) {
                errors.add("node '" + n.id() + "' has type '" + n.type() + "'; allowed: " + ALLOWED);
            }
            if (n.risk() != null) {
                try {
                    RiskLevel.valueOf(n.risk());
                } catch (IllegalArgumentException e) {
                    errors.add("node '" + n.id() + "' has unknown risk '" + n.risk() + "'");
                }
            }
            for (String ac : n.acceptanceCriteria()) {
                if (!spec.criterionIds().contains(ac)) {
                    errors.add("node '" + n.id() + "' refers to unknown criterion " + ac);
                }
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        Plan structural;
        try {
            structural = new Plan("proposal", 1, nodes.stream().map(n -> TaskNode.builder(n.id(), type(n.type()))
                    .executor("x").dependsOn(n.dependsOn().toArray(String[]::new)).build()).toList());
        } catch (PlanValidationException e) {
            return e.errors();
        } catch (IllegalArgumentException e) {
            return List.of(e.getMessage());
        }

        Map<String, PlanProposal.ProposedNode> byId = nodes.stream()
                .collect(Collectors.toMap(PlanProposal.ProposedNode::id, Function.identity()));
        List<String> implement = ofType(nodes, NodeType.IMPLEMENT, NodeType.SCHEMA_MIGRATION);
        List<String> tests = ofType(nodes, NodeType.TEST);
        List<String> designs = ofType(nodes, NodeType.DESIGN);
        List<String> joins = ofType(nodes, NodeType.JOIN);
        List<String> releases = ofType(nodes, NodeType.RELEASE_READINESS);

        for (String ac : spec.criterionIds()) {
            if (implement.stream().noneMatch(id -> byId.get(id).acceptanceCriteria().contains(ac))) {
                errors.add("criterion " + ac + " is not covered by any IMPLEMENT node");
            }
            if (tests.stream().noneMatch(id -> byId.get(id).acceptanceCriteria().contains(ac))) {
                errors.add("criterion " + ac + " is not covered by any TEST node");
            }
        }
        if (designs.size() != 1) {
            errors.add("plan needs exactly one DESIGN node, has " + designs.size());
        } else {
            Set<String> afterDesign = new HashSet<>(structural.downstreamInclusive(designs.get(0)));
            for (String id : concat(implement, tests)) {
                if (!afterDesign.contains(id)) errors.add("'" + id + "' must come after the DESIGN node");
            }
        }
        for (String test : tests) {
            Set<String> upstream = upstream(structural, test);
            for (String impl : implement) {
                if (upstream.contains(impl)) {
                    errors.add("TEST node '" + test + "' depends on IMPLEMENT node '" + impl
                            + "'; tests must be written from the criteria, not from the code");
                }
            }
        }
        boolean joined = joins.stream().anyMatch(j -> upstream(structural, j).containsAll(concat(implement, tests)));
        if (!joined) {
            errors.add("plan needs a JOIN node downstream of every IMPLEMENT and TEST node");
        }
        if (releases.size() != 1) {
            errors.add("plan needs exactly one RELEASE_READINESS node, has " + releases.size());
        } else {
            String release = releases.get(0);
            if (!structural.dependentsOf(release).isEmpty()) errors.add("RELEASE_READINESS must be the last node");
            Set<String> up = upstream(structural, release);
            for (PlanProposal.ProposedNode n : nodes) {
                if (!n.id().equals(release) && !up.contains(n.id())) {
                    errors.add("'" + n.id() + "' does not lead to release readiness");
                }
            }
        }
        return errors;
    }

    static NodeType type(String t) {
        try {
            return t == null ? null : NodeType.valueOf(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> ofType(List<PlanProposal.ProposedNode> nodes, NodeType... types) {
        Set<NodeType> set = EnumSet.of(types[0], types);
        return nodes.stream().filter(n -> set.contains(type(n.type()))).map(PlanProposal.ProposedNode::id).toList();
    }

    private static Set<String> upstream(Plan plan, String id) {
        Set<String> out = new HashSet<>();
        List<String> stack = new ArrayList<>(plan.node(id).dependsOn());
        while (!stack.isEmpty()) {
            String cur = stack.remove(stack.size() - 1);
            if (out.add(cur)) stack.addAll(plan.node(cur).dependsOn());
        }
        return out;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
