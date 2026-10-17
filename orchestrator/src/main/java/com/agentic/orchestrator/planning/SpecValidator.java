package com.agentic.orchestrator.planning;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SpecValidator {
    private SpecValidator() {}

    public static List<String> validate(RequirementSpec spec) {
        List<String> errors = new ArrayList<>();
        if (spec.title() == null || spec.title().isBlank()) errors.add("title is missing");
        if (spec.acceptanceCriteria().isEmpty()) errors.add("no acceptance criteria");
        if (spec.acceptanceCriteria().size() > 20) errors.add("more than 20 acceptance criteria; split the requirement");
        Set<String> ids = new HashSet<>();
        for (RequirementSpec.AcceptanceCriterion ac : spec.acceptanceCriteria()) {
            if (ac.id() == null || !ac.id().matches("AC[0-9]{1,2}")) errors.add("criterion id '" + ac.id() + "' must look like AC1");
            if (!ids.add(String.valueOf(ac.id()))) errors.add("duplicate criterion id " + ac.id());
            if (blank(ac.given()) || blank(ac.when()) || blank(ac.then())) {
                errors.add(ac.id() + " needs given, when and then");
            }
        }
        for (RequirementSpec.Ambiguity a : spec.ambiguities()) {
            if (blank(a.question())) errors.add("an ambiguity has no question");
            if (a.confidence() == null || !a.confidence().matches("(?i)LOW|MEDIUM|HIGH")) {
                errors.add("ambiguity '" + a.question() + "' needs confidence LOW, MEDIUM or HIGH");
            }
        }
        return errors;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
