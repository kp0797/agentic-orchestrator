package com.agentic.orchestrator.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A validated, immutable task DAG. Construction fails with every structural problem found:
 * duplicate ids, unknown dependencies, self-dependencies and cycles.
 */
public final class Plan {
    private final String id;
    private final int version;
    private final Map<String, TaskNode> nodes;
    private final List<String> topologicalOrder;
    private final Map<String, List<String>> dependents;

    public Plan(String id, int version, List<TaskNode> nodeList) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("plan id required");
        }
        this.id = id;
        this.version = version;
        List<String> errors = new ArrayList<>();
        Map<String, TaskNode> byId = new LinkedHashMap<>();
        for (TaskNode n : nodeList) {
            if (byId.putIfAbsent(n.id(), n) != null) {
                errors.add("duplicate node id '" + n.id() + "'");
            }
        }
        if (byId.isEmpty()) {
            errors.add("plan has no nodes");
        }
        for (TaskNode n : byId.values()) {
            Set<String> seen = new HashSet<>();
            for (String dep : n.dependsOn()) {
                if (dep.equals(n.id())) {
                    errors.add("node '" + n.id() + "' depends on itself");
                } else if (!byId.containsKey(dep)) {
                    errors.add("node '" + n.id() + "' depends on unknown node '" + dep + "'");
                }
                if (!seen.add(dep)) {
                    errors.add("node '" + n.id() + "' lists dependency '" + dep + "' twice");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new PlanValidationException(errors);
        }
        Map<String, List<String>> deps = new LinkedHashMap<>();
        byId.keySet().forEach(k -> deps.put(k, new ArrayList<>()));
        byId.values().forEach(n -> n.dependsOn().forEach(d -> deps.get(d).add(n.id())));
        List<String> order = kahn(byId, deps);
        if (order.size() != byId.size()) {
            Set<String> inCycle = new TreeSet<>(byId.keySet());
            inCycle.removeAll(order);
            throw new PlanValidationException(List.of("dependency cycle among nodes " + inCycle));
        }
        this.nodes = Collections.unmodifiableMap(byId);
        this.topologicalOrder = List.copyOf(order);
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        deps.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        this.dependents = Collections.unmodifiableMap(frozen);
    }

    private static List<String> kahn(Map<String, TaskNode> byId, Map<String, List<String>> dependents) {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        byId.values().forEach(n -> inDegree.put(n.id(), n.dependsOn().size()));
        Deque<String> queue = new ArrayDeque<>();
        inDegree.forEach((k, v) -> { if (v == 0) queue.add(k); });
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String next = queue.poll();
            order.add(next);
            for (String child : dependents.get(next)) {
                if (inDegree.merge(child, -1, Integer::sum) == 0) {
                    queue.add(child);
                }
            }
        }
        return order;
    }

    public String id() { return id; }
    public int version() { return version; }
    public Map<String, TaskNode> nodes() { return nodes; }
    public TaskNode node(String nodeId) {
        TaskNode n = nodes.get(nodeId);
        if (n == null) {
            throw new IllegalArgumentException("unknown node " + nodeId);
        }
        return n;
    }
    public List<String> topologicalOrder() { return topologicalOrder; }
    public List<String> dependentsOf(String nodeId) { return dependents.get(nodeId); }

    /** The node and everything downstream of it, in topological order. */
    public List<String> downstreamInclusive(String nodeId) {
        node(nodeId);
        Set<String> reach = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(nodeId);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (reach.add(cur)) {
                dependents.get(cur).forEach(stack::push);
            }
        }
        return topologicalOrder.stream().filter(reach::contains).toList();
    }

    /** A stable digest of the plan's structure, recorded in the audit log at acceptance. */
    public String digest() {
        StringBuilder sb = new StringBuilder(id).append('|').append(version);
        for (String nodeId : topologicalOrder) {
            TaskNode n = nodes.get(nodeId);
            sb.append('\n').append(n.id()).append('|').append(n.type()).append('|').append(n.risk())
              .append('|').append(n.requiresApproval()).append('|').append(n.executorChain())
              .append('|').append(n.dependsOn()).append('|').append(n.exitGates())
              .append('|').append(n.retryPolicy().maxAttempts());
        }
        return com.agentic.orchestrator.artifacts.Hashing.sha256(sb.toString());
    }
}
