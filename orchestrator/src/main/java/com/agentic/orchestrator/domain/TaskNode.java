package com.agentic.orchestrator.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One unit of work in a plan. Immutable: a re-plan produces new nodes, it never mutates these.
 *
 * @param executorKey          the agent (or deterministic step) that does the work
 * @param fallbackExecutorKeys tried in order once the primary executor exhausts its retries
 * @param exitGates            ids of verifiers that must all pass before the node can succeed
 * @param requiresApproval     planner's request for a human checkpoint; policy may add one too
 */
public record TaskNode(
        String id,
        NodeType type,
        String description,
        List<String> dependsOn,
        RiskLevel risk,
        boolean requiresApproval,
        String executorKey,
        List<String> fallbackExecutorKeys,
        List<String> exitGates,
        List<String> acceptanceCriteria,
        RetryPolicy retryPolicy) {

    public TaskNode {
        requireId(id);
        Objects.requireNonNull(type, "type");
        description = description == null ? "" : description;
        dependsOn = List.copyOf(dependsOn == null ? List.of() : dependsOn);
        risk = risk == null ? RiskLevel.LOW : risk;
        Objects.requireNonNull(executorKey, "executorKey");
        fallbackExecutorKeys = List.copyOf(fallbackExecutorKeys == null ? List.of() : fallbackExecutorKeys);
        exitGates = List.copyOf(exitGates == null ? List.of() : exitGates);
        acceptanceCriteria = List.copyOf(acceptanceCriteria == null ? List.of() : acceptanceCriteria);
        retryPolicy = retryPolicy == null ? RetryPolicy.DEFAULT : retryPolicy;
    }

    private static void requireId(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("node id must match [a-z0-9][a-z0-9-]{0,63}: " + id);
        }
    }

    /** Primary executor followed by fallbacks. */
    public List<String> executorChain() {
        List<String> chain = new ArrayList<>();
        chain.add(executorKey);
        chain.addAll(fallbackExecutorKeys);
        return List.copyOf(chain);
    }

    /** Total attempts across the whole fallback chain. */
    public int maxTotalAttempts() {
        return executorChain().size() * retryPolicy.maxAttempts();
    }

    /** Index into {@link #executorChain()} for a 1-based overall attempt number. */
    public int executorIndexForAttempt(int attempt) {
        return Math.min((attempt - 1) / retryPolicy.maxAttempts(), executorChain().size() - 1);
    }

    /** 1-based attempt number within the current executor, used for backoff. */
    public int attemptWithinExecutor(int attempt) {
        return ((attempt - 1) % retryPolicy.maxAttempts()) + 1;
    }

    public static Builder builder(String id, NodeType type) {
        return new Builder(id, type);
    }

    public static final class Builder {
        private final String id;
        private final NodeType type;
        private String description = "";
        private List<String> dependsOn = List.of();
        private RiskLevel risk = RiskLevel.LOW;
        private boolean requiresApproval;
        private String executorKey;
        private List<String> fallbacks = List.of();
        private List<String> exitGates = List.of();
        private List<String> acceptanceCriteria = List.of();
        private RetryPolicy retryPolicy = RetryPolicy.DEFAULT;

        private Builder(String id, NodeType type) {
            this.id = id;
            this.type = type;
            this.executorKey = type.name().toLowerCase();
        }

        public Builder description(String v) { this.description = v; return this; }
        public Builder dependsOn(String... v) { this.dependsOn = List.of(v); return this; }
        public Builder risk(RiskLevel v) { this.risk = v; return this; }
        public Builder requiresApproval(boolean v) { this.requiresApproval = v; return this; }
        public Builder executor(String v) { this.executorKey = v; return this; }
        public Builder fallbacks(String... v) { this.fallbacks = List.of(v); return this; }
        public Builder exitGates(String... v) { this.exitGates = List.of(v); return this; }
        public Builder acceptanceCriteria(String... v) { this.acceptanceCriteria = List.of(v); return this; }
        public Builder retryPolicy(RetryPolicy v) { this.retryPolicy = v; return this; }

        public TaskNode build() {
            return new TaskNode(id, type, description, dependsOn, risk, requiresApproval, executorKey,
                    fallbacks, exitGates, acceptanceCriteria, retryPolicy);
        }
    }
}
