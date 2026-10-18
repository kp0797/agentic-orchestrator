package com.agentic.orchestrator.codebase;

import java.util.List;

/**
 * What a change is likely to touch, derived deterministically from the repository index and the
 * requirement text. It guides the planner and the agents; it is evidence, not an instruction.
 *
 * @param seeds    types the requirement most directly names, with the words that matched
 * @param impacted every type in scope, with why (seed, uses a seed, used by a seed)
 * @param tests    existing tests that exercise impacted types: they must keep passing
 */
public record ImpactReport(boolean emptyProject, int mainTypes, int testTypes, List<Item> seeds,
                           List<Item> impacted, List<Item> tests, List<String> endpoints, List<String> tables,
                           List<String> migrations, int nextMigrationVersion, List<String> risks) {

    public record Item(String type, String file, String reason) {}

    public List<String> impactedFiles() {
        return impacted.stream().map(Item::file).distinct().toList();
    }

    public String markdown() {
        if (emptyProject) {
            return "New project: there is no existing source code to change.";
        }
        StringBuilder sb = new StringBuilder("Existing project: ").append(mainTypes).append(" main types, ")
                .append(testTypes).append(" test types.\n");
        sb.append("\nMost relevant to the requirement:\n");
        seeds.forEach(i -> sb.append("- ").append(i.type()).append(" (").append(i.file()).append("): ")
                .append(i.reason()).append('\n'));
        sb.append("\nIn scope of the change:\n");
        impacted.forEach(i -> sb.append("- ").append(i.type()).append(" (").append(i.file()).append("): ")
                .append(i.reason()).append('\n'));
        if (!endpoints.isEmpty()) {
            sb.append("\nAffected HTTP endpoints: ").append(String.join(", ", endpoints)).append('\n');
        }
        if (!tables.isEmpty()) {
            sb.append("Database tables involved: ").append(String.join(", ", tables)).append('\n');
        }
        if (!migrations.isEmpty()) {
            sb.append("Existing Flyway migrations (already applied in production; never edit them): ")
                    .append(String.join(", ", migrations)).append(". The next migration is V")
                    .append(nextMigrationVersion).append(".\n");
        }
        sb.append("\nExisting tests covering this code (must keep passing):\n");
        if (tests.isEmpty()) {
            sb.append("- none\n");
        }
        tests.forEach(i -> sb.append("- ").append(i.type()).append(" (").append(i.file()).append(")\n"));
        if (!risks.isEmpty()) {
            sb.append("\nRisks:\n");
            risks.forEach(r -> sb.append("- ").append(r).append('\n'));
        }
        return sb.toString();
    }
}
