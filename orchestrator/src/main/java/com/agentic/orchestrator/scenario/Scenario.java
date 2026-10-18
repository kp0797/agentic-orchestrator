package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.domain.RunBudget;
import com.agentic.orchestrator.engine.FaultSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A scenario: a requirement, the project it starts from, its recorded model answers and any
 * deliberate faults. Loaded from {@code scenarios/<name>/scenario.yaml}.
 *
 * @param regressionProof the merged change's new tests must fail on the starting code (defect fixes)
 * @param stakeholder     scripted human messages for unattended runs: what a stakeholder says when a
 *                        given node asks for approval. Labelled as scripted in the audit log.
 */
public record Scenario(String name, String description, String requirement, Path template, Path cassette,
                       RunBudget budget, List<FaultSpec> faults, boolean regressionProof,
                       List<StakeholderMessage> stakeholder) {

    /** @param at the node whose approval request the message answers; {@code from} who says it */
    public record StakeholderMessage(String at, String from, String message) {}

    public Scenario {
        faults = List.copyOf(faults == null ? List.of() : faults);
        stakeholder = List.copyOf(stakeholder == null ? List.of() : stakeholder);
    }

    public Scenario(String name, String description, String requirement, Path template, Path cassette,
                    RunBudget budget, List<FaultSpec> faults) {
        this(name, description, requirement, template, cassette, budget, faults, false, List.of());
    }

    /** The same scenario with more deliberate faults (from the command line). */
    public Scenario withFaults(List<FaultSpec> extra) {
        List<FaultSpec> all = new ArrayList<>(faults);
        all.addAll(extra);
        return new Scenario(name, description, requirement, template, cassette, budget, all, regressionProof,
                stakeholder);
    }

    public Scenario withCassette(Path other) {
        return new Scenario(name, description, requirement, template, other, budget, faults, regressionProof,
                stakeholder);
    }

    /** Without the scripted stakeholder: a person answers instead (the approval console). */
    public Scenario withoutScriptedStakeholder() {
        return new Scenario(name, description, requirement, template, cassette, budget, faults, regressionProof,
                List.of());
    }
}
