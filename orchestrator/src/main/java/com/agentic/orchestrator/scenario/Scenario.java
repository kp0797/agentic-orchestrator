package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.domain.RunBudget;
import com.agentic.orchestrator.engine.FaultSpec;

import java.nio.file.Path;
import java.util.List;

/**
 * A scenario: a requirement, the project it starts from, its recorded model answers and any
 * deliberate faults. Loaded from {@code scenarios/<name>/scenario.yaml}. {@code regressionProof}: the
 * merged change's new tests must fail on the starting code (for defect fixes).
 */
public record Scenario(String name, String description, String requirement, Path template, Path cassette,
                       RunBudget budget, List<FaultSpec> faults, boolean regressionProof) {
    public Scenario {
        faults = List.copyOf(faults == null ? List.of() : faults);
    }

    public Scenario(String name, String description, String requirement, Path template, Path cassette,
                    RunBudget budget, List<FaultSpec> faults) {
        this(name, description, requirement, template, cassette, budget, faults, false);
    }

    /** The same scenario with more deliberate faults (from the command line). */
    public Scenario withFaults(List<FaultSpec> extra) {
        List<FaultSpec> all = new java.util.ArrayList<>(faults);
        all.addAll(extra);
        return new Scenario(name, description, requirement, template, cassette, budget, all, regressionProof);
    }

    public Scenario withCassette(Path other) {
        return new Scenario(name, description, requirement, template, other, budget, faults, regressionProof);
    }
}
