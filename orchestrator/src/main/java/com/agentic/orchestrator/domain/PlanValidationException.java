package com.agentic.orchestrator.domain;

import java.util.List;

/** A plan was rejected before any work ran. Carries every problem found, not just the first. */
public class PlanValidationException extends RuntimeException {
    private final List<String> errors;

    public PlanValidationException(List<String> errors) {
        super("Plan rejected: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
