package com.agentic.orchestrator.gates;

import java.nio.file.Path;
import java.util.List;

/**
 * The code change a node made: a commit on its own branch, relative to the base it started from.
 * Gates inspect this; they never see the agent's reasoning, only what it changed.
 */
public record WorkspaceRef(Path directory, String branch, String baseCommit, String headCommit,
                           List<String> changedFiles, String diff, int addedLines, int removedLines) {
    public WorkspaceRef {
        changedFiles = List.copyOf(changedFiles);
        diff = diff == null ? "" : diff;
    }

    public boolean changed() {
        return !baseCommit.equals(headCommit);
    }
}
