package com.agentic.orchestrator.domain;

public enum RunStatus {
    CREATED,
    RUNNING,
    /** Nothing runnable is left except nodes waiting for a human decision. */
    PAUSED_FOR_APPROVAL,
    /** Safe-stop: kill switch, budget or critical policy violation. Resumable. */
    STOPPED,
    SUCCEEDED,
    FAILED,
    ROLLED_BACK
}
