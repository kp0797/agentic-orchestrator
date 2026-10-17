package com.agentic.orchestrator.workspace;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Optional;

/**
 * Least-privilege write access for one agent. Enforced in the tool layer, so a prompt can never
 * talk an agent past it. Protected paths win over everything, including an agent's own allowlist.
 */
public final class PathPermissions {
    private final List<String> writable;
    private final List<String> protectedGlobs;
    private final List<PathMatcher> writableMatchers;
    private final List<PathMatcher> protectedMatchers;

    public PathPermissions(List<String> writable, List<String> protectedGlobs) {
        this.writable = List.copyOf(writable);
        this.protectedGlobs = List.copyOf(protectedGlobs);
        this.writableMatchers = this.writable.stream().map(PathPermissions::matcher).toList();
        this.protectedMatchers = this.protectedGlobs.stream().map(PathPermissions::matcher).toList();
    }

    public static PathMatcher matcher(String glob) {
        return FileSystems.getDefault().getPathMatcher("glob:" + glob);
    }

    /** Empty when the write is allowed, otherwise the reason it is refused. */
    public Optional<String> check(String relativePath) {
        Path p = Path.of(relativePath);
        for (int i = 0; i < protectedMatchers.size(); i++) {
            if (protectedMatchers.get(i).matches(p)) {
                return Optional.of("'" + relativePath + "' is protected (" + protectedGlobs.get(i) + ")");
            }
        }
        if (writableMatchers.stream().noneMatch(m -> m.matches(p))) {
            return Optional.of("'" + relativePath + "' is outside this agent's writable paths " + writable);
        }
        return Optional.empty();
    }

    public List<String> writable() {
        return writable;
    }

    public List<String> protectedPaths() {
        return protectedGlobs;
    }
}
