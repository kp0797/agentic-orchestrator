package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.domain.RunBudget;
import com.agentic.orchestrator.engine.FaultSpec;

import java.nio.file.Path;
import java.util.List;

/**
 * A scenario: a requirement, the project it starts from, its recorded model answers and any
 * deliberate faults. Loaded from {@code scenarios/<name>/scenario.yaml}.
 */
public record Scenario(String name, String description, String requirement, Path template, Path cassette,
                       RunBudget budget, List<FaultSpec> faults) {
    public Scenario {
        faults = List.copyOf(faults == null ? List.of() : faults);
    }
}
