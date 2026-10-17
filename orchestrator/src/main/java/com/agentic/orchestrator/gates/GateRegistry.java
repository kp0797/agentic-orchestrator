package com.agentic.orchestrator.gates;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class GateRegistry {
    private final Map<String, Gate> gates = new LinkedHashMap<>();

    public GateRegistry register(Gate gate) {
        if (gates.putIfAbsent(gate.id(), gate) != null) {
            throw new IllegalArgumentException("gate already registered: " + gate.id());
        }
        return this;
    }

    public Optional<Gate> find(String id) {
        return Optional.ofNullable(gates.get(id));
    }

    public Set<String> ids() {
        return gates.keySet();
    }

    /** The gates every deployment has, available without any external tool. */
    public static GateRegistry withBuiltIns() {
        return new GateRegistry()
                .register(new OutputsPresentGate())
                .register(new NoSecretsGate());
    }
}
