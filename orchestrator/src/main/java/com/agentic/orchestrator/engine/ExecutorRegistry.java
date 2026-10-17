package com.agentic.orchestrator.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ExecutorRegistry {
    private final Map<String, NodeExecutor> executors = new LinkedHashMap<>();

    public ExecutorRegistry register(NodeExecutor executor) {
        if (executors.putIfAbsent(executor.key(), executor) != null) {
            throw new IllegalArgumentException("executor already registered: " + executor.key());
        }
        return this;
    }

    public Optional<NodeExecutor> find(String key) {
        return Optional.ofNullable(executors.get(key));
    }

    public NodeExecutor get(String key) {
        return find(key).orElseThrow(() -> new IllegalArgumentException("unknown executor " + key));
    }

    public Set<String> keys() {
        return executors.keySet();
    }
}
