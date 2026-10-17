package com.agentic.orchestrator.workspace;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Owns one {@link RunWorkspace} per run under a base directory (runs/&lt;runId&gt;/). */
public final class WorkspaceManager {
    private final Path baseDir;
    private final Map<String, RunWorkspace> workspaces = new ConcurrentHashMap<>();
    private final Map<String, Map<String, RunWorkspace.Worktree>> worktrees = new ConcurrentHashMap<>();

    public WorkspaceManager(Path baseDir) {
        this.baseDir = baseDir;
    }

    public RunWorkspace create(String runId, Path template) {
        return workspaces.computeIfAbsent(runId, id -> RunWorkspace.create(baseDir.resolve(id), template));
    }

    /** A child run (execution after planning) continues in the parent's repository. */
    public RunWorkspace share(String parentRunId, String childRunId) {
        RunWorkspace ws = get(parentRunId);
        workspaces.put(childRunId, ws);
        return ws;
    }

    public RunWorkspace get(String runId) {
        RunWorkspace ws = workspaces.get(runId);
        if (ws == null) {
            throw new IllegalStateException("no workspace for run " + runId);
        }
        return ws;
    }

    public boolean has(String runId) {
        return workspaces.containsKey(runId);
    }

    public void remember(String runId, RunWorkspace.Worktree wt) {
        worktrees.computeIfAbsent(runId, k -> new ConcurrentHashMap<>()).put(wt.nodeId(), wt);
    }

    public RunWorkspace.Worktree worktree(String runId, String nodeId) {
        RunWorkspace.Worktree wt = worktrees.getOrDefault(runId, Map.of()).get(nodeId);
        if (wt == null) {
            throw new IllegalStateException("no worktree for " + runId + "/" + nodeId);
        }
        return wt;
    }

    public Path baseDir() {
        return baseDir;
    }
}
