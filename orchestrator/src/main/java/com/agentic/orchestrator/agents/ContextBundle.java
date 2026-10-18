package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.workspace.PathPermissions;
import com.agentic.orchestrator.workspace.RunWorkspace;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What an agent gets to see: a list of all files, plus the contents of the files its role needs,
 * in priority order, up to a size cap. The manifest records what was cut, so when an agent gets
 * something wrong you can check whether it ever saw the file it needed.
 */
public record ContextBundle(String fileList, String contents, List<String> included, List<String> omitted,
                            int chars, int maxChars) {

    public static ContextBundle build(Path dir, List<String> allFiles, List<String> includeGlobs, int maxChars) {
        return build(dir, allFiles, includeGlobs, List.of(), maxChars);
    }

    /**
     * @param preferred files to include first (for example those an impact analysis put in scope); only
     *                  those the role's globs allow, so a preference never widens what a role may see
     */
    public static ContextBundle build(Path dir, List<String> allFiles, List<String> includeGlobs,
                                      List<String> preferred, int maxChars) {
        Set<String> ordered = new LinkedHashSet<>();
        List<PathMatcher> visible = includeGlobs.stream().map(PathPermissions::matcher).toList();
        for (String f : preferred) {
            if (allFiles.contains(f) && visible.stream().anyMatch(m -> m.matches(Path.of(f)))) {
                ordered.add(f);
            }
        }
        for (String glob : includeGlobs) {
            PathMatcher m = PathPermissions.matcher(glob);
            allFiles.stream().filter(f -> m.matches(Path.of(f))).sorted().forEach(ordered::add);
        }
        StringBuilder sb = new StringBuilder();
        List<String> included = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        for (String f : ordered) {
            String text = RunWorkspace.readText(dir.resolve(f));
            String block = "=== " + f + " ===\n" + text + (text.endsWith("\n") ? "" : "\n");
            if (sb.length() + block.length() <= maxChars) {
                sb.append(block);
                included.add(f);
            } else {
                omitted.add(f);
            }
        }
        return new ContextBundle(String.join("\n", allFiles), sb.isEmpty() ? "(no files yet)" : sb.toString(),
                included, omitted, sb.length(), maxChars);
    }

    public String manifestJson() {
        return com.agentic.orchestrator.planning.PlanningJson.write(java.util.Map.of(
                "included", included, "omitted", omitted, "chars", chars, "maxChars", maxChars,
                "approxTokens", chars / 4));
    }
}
