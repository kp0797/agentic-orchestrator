package com.agentic.orchestrator.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class FaultInjector {
    public static final FaultInjector NONE = new FaultInjector(List.of());

    private final List<FaultSpec> faults;

    public FaultInjector(List<FaultSpec> faults) {
        this.faults = List.copyOf(faults);
    }

    public static FaultInjector of(FaultSpec... faults) {
        return new FaultInjector(List.of(faults));
    }

    public Optional<FaultSpec> lookup(String nodeId, int attempt) {
        return faults.stream()
                .filter(f -> f.nodeId().equals(nodeId) && f.attempts().contains(attempt))
                .findFirst();
    }

    public FaultInjector plus(FaultSpec spec) {
        List<FaultSpec> all = new ArrayList<>(faults);
        all.add(spec);
        return new FaultInjector(all);
    }
}
