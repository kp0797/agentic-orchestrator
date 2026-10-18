package com.agentic.orchestrator.evidence;

import com.agentic.orchestrator.artifacts.Artifact;
import com.agentic.orchestrator.domain.Plan;
import com.agentic.orchestrator.domain.TaskNode;
import com.agentic.orchestrator.events.AuditVerifier;
import com.agentic.orchestrator.events.Event;
import com.agentic.orchestrator.events.EventType;
import com.agentic.orchestrator.metrics.RunMetrics;
import com.agentic.orchestrator.planning.PlanningJson;
import com.agentic.orchestrator.scenario.Orchestrator;
import com.agentic.orchestrator.workspace.GitCli;
import com.agentic.orchestrator.workspace.RunWorkspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a reviewer needs to judge one scenario run without trusting the console: a readable
 * summary, the full hash-chained audit logs, every artifact with its lineage, the final code change
 * and the metrics. All of it is derived from the audit log and the artifact store, never from in-memory
 * state, so the pack shows what was recorded, not what the code believes happened.
 *
 * <pre>
 * evidence/
 *   README.md            what ran, decisions, failures and recoveries, integrity
 *   audit/&lt;run&gt;.jsonl     the audit log, one event per line (check with: verify-evidence &lt;dir&gt;)
 *   artifacts/...        each artifact as produced, under run/node/attempt
 *   manifest.json        artifact hashes and lineage (producer, inputs, model, prompt hash)
 *   change.diff          the final code change against the starting code
 *   metrics.json         reliability metrics per run
 * </pre>
 */
public final class EvidencePack {
    static final int MAX_ARTIFACT_BYTES = 512 * 1024;

    private EvidencePack() {}

    /** @param mode replay, record or live: shown first, because it decides what the evidence proves */
    public static Path write(Orchestrator o, Path dir, String mode) {
        try {
            Files.createDirectories(dir.resolve("audit"));
            List<RunView> runs = runs(o);
            List<Map<String, Object>> manifest = new ArrayList<>();
            Map<String, Object> metrics = new LinkedHashMap<>();
            for (RunView r : runs) {
                List<Event> log = o.events().events(r.runId());
                Files.writeString(dir.resolve("audit").resolve(r.runId() + ".jsonl"), AuditFile.encode(log));
                manifest.addAll(artifacts(o, r, log, dir));
                metrics.put(r.runId(), RunMetrics.from(log).toMap());
            }
            Files.writeString(dir.resolve("manifest.json"), PlanningJson.write(manifest));
            Files.writeString(dir.resolve("metrics.json"), PlanningJson.write(metrics));
            Optional<String> diff = finalDiff(o, runs);
            diff.ifPresent(d -> write(dir.resolve("change.diff"), d));
            Files.writeString(dir.resolve("README.md"), readme(o, runs, mode, diff.isPresent(), manifest.size()));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write evidence to " + dir, e);
        }
    }

    record RunView(String runId, String role, Plan plan) {}

    static List<RunView> runs(Orchestrator o) {
        List<RunView> out = new ArrayList<>();
        o.planningRunId().ifPresent(id -> out.add(new RunView(id, "planning", o.plans().load(id).plan())));
        o.supersededRuns().forEach(id -> out.add(new RunView(id, "execution (superseded by a re-plan)",
                o.plans().load(id).plan())));
        o.executionRunId().ifPresent(id -> out.add(new RunView(id, "execution", o.plans().load(id).plan())));
        return out;
    }

    private static List<Map<String, Object>> artifacts(Orchestrator o, RunView r, List<Event> log, Path dir)
            throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Event e : log) {
            if (e.type() != EventType.ARTIFACT_PRODUCED) {
                continue;
            }
            Optional<Artifact> a = o.artifacts().get(e.get("hash"));
            if (a.isEmpty()) {
                continue;
            }
            Artifact art = a.get();
            String rel = "artifacts/" + r.runId() + "/" + e.nodeId() + "/attempt-" + art.lineage().attempt() + "/"
                    + art.name();
            if (art.content().length <= MAX_ARTIFACT_BYTES) {
                Path p = dir.resolve(rel);
                Files.createDirectories(p.getParent());
                Files.write(p, art.content());
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", rel);
            m.put("hash", art.hash());
            m.put("run", r.runId());
            m.put("node", e.nodeId());
            m.put("attempt", art.lineage().attempt());
            m.put("executor", art.lineage().executorKey());
            m.put("inputs", art.lineage().inputHashes());
            m.put("model", art.lineage().model());
            m.put("promptHash", art.lineage().promptHash());
            out.add(m);
        }
        return out;
    }

    /** The change from the starting code to the last committed state of the final execution run. */
    static Optional<String> finalDiff(Orchestrator o, List<RunView> runs) {
        Optional<String> execution = o.executionRunId();
        if (execution.isEmpty() || !o.workspaces().has(execution.get())) {
            return Optional.empty();
        }
        RunWorkspace ws = o.workspaces().get(execution.get());
        Plan plan = runs.get(runs.size() - 1).plan();
        List<String> order = new ArrayList<>(plan.topologicalOrder());
        java.util.Collections.reverse(order);
        for (String nodeId : order) {
            try {
                RunWorkspace.Worktree wt = o.workspaces().worktree(execution.get(), nodeId);
                String head = GitCli.run(wt.directory(), "rev-parse", "HEAD").strip();
                String stat = GitCli.run(wt.directory(), "diff", "--stat", ws.initialCommit(), head);
                String diff = GitCli.run(wt.directory(), "diff", ws.initialCommit(), head);
                return Optional.of("# Final change: starting code -> branch " + wt.branch() + " (" + head.substring(0, 8)
                        + ")\n# " + stat.strip().replace("\n", "\n# ") + "\n\n" + diff);
            } catch (IllegalStateException e) {
                // this node kept no worktree (deterministic steps); try the one before it
            }
        }
        return Optional.empty();
    }

    private static String readme(Orchestrator o, List<RunView> runs, String mode, boolean hasDiff, int artifacts) {
        StringBuilder sb = new StringBuilder();
        RunView last = runs.get(runs.size() - 1);
        String result = o.engine().state(last.runId()).status().name();
        sb.append("# Evidence: ").append(o.scenario().name()).append(" - ").append(result).append("\n\n");
        sb.append("Generated ").append(Instant.now()).append(" in **").append(mode).append("** mode. ");
        sb.append(switch (mode.toLowerCase()) {
            case "replay" -> "Model answers came from the scenario's cassette; no model was called. Gates, builds, "
                    + "tests, policy, approvals and the audit log ran for real.";
            case "record" -> "Model answers came from live calls and were recorded to the cassette.";
            default -> "Model answers came from live calls.";
        }).append("\n\n> ").append(o.scenario().description().strip()).append("\n\n");

        sb.append("## Runs\n\n| Run | Role | Plan | Status | Audit chain |\n| --- | --- | --- | --- | --- |\n");
        for (RunView r : runs) {
            AuditVerifier.Result audit = AuditVerifier.verify(o.events().events(r.runId()));
            sb.append("| `").append(r.runId()).append("` | ").append(r.role()).append(" | v").append(r.plan().version())
                    .append(" (").append(r.plan().topologicalOrder().size()).append(" nodes) | ")
                    .append(o.engine().state(r.runId()).status()).append(" | ")
                    .append(audit.valid() ? "valid, " + audit.checked() + " events, head `"
                            + audit.headHash().substring(0, 16) + "`" : "**BROKEN** at " + audit.firstBadSeq())
                    .append(" |\n");
        }

        for (RunView r : runs) {
            List<Event> log = o.events().events(r.runId());
            sb.append("\n## ").append(capitalize(r.role())).append(": `").append(r.runId()).append("`\n\n");
            sb.append("| Node | Type | Risk | Status | Attempts | Gates passed / failed |\n| --- | --- | --- | --- | --- | --- |\n");
            for (String id : r.plan().topologicalOrder()) {
                TaskNode n = r.plan().node(id);
                var rt = o.engine().state(r.runId()).node(id);
                long passed = log.stream().filter(e -> isGate(e, id) && "true".equals(e.get("passed"))).count();
                long failed = log.stream().filter(e -> isGate(e, id) && "false".equals(e.get("passed"))).count();
                sb.append("| ").append(id).append(" | ").append(n.type()).append(" | ")
                        .append(rt.effectiveRisk() == null ? n.risk() : rt.effectiveRisk()).append(" | ")
                        .append(rt.status()).append(" | ").append(rt.attempts()).append(" | ")
                        .append(passed).append(" / ").append(failed).append(" |\n");
            }
            section(sb, "Human input", log, List.of(EventType.CLARIFICATION_RECORDED, EventType.APPROVAL_GRANTED,
                    EventType.APPROVAL_REJECTED, EventType.APPROVAL_INVALIDATED));
            section(sb, "What went wrong and how it was handled", log, List.of(EventType.FAULT_INJECTED,
                    EventType.NODE_ATTEMPT_FAILED, EventType.FALLBACK_ACTIVATED, EventType.NODE_RESET,
                    EventType.COMPENSATION_EXECUTED, EventType.COMPENSATION_FAILED, EventType.BUDGET_EXCEEDED,
                    EventType.RUN_STOPPED, EventType.RUN_FAILED));
            section(sb, "Decisions recorded by agents", log, List.of(EventType.DECISION_RECORDED));
            sb.append("\nMetrics: `").append(RunMetrics.from(log).summary()).append("`\n");
        }

        sb.append("\n## Files\n\n");
        sb.append("- `audit/<run>.jsonl`: the complete audit logs. `verify-evidence <this directory>` re-checks every"
                + " hash chain from these files alone; editing any line breaks it.\n");
        sb.append("- `manifest.json`: ").append(artifacts).append(" artifacts with their lineage (producer, inputs, "
                + "model, prompt hash); the files are under `artifacts/`.\n");
        if (hasDiff) {
            sb.append("- `change.diff`: the final code change against the starting code.\n");
        }
        sb.append("- `metrics.json`: the metrics above, per run.\n");
        return sb.toString();
    }

    private static void section(StringBuilder sb, String title, List<Event> log, List<EventType> types) {
        List<Event> hits = log.stream().filter(e -> types.contains(e.type())).toList();
        if (hits.isEmpty()) {
            return;
        }
        sb.append("\n### ").append(title).append("\n\n");
        for (Event e : hits) {
            sb.append("- `").append(e.type()).append("`").append(e.nodeId().isEmpty() ? "" : " on **" + e.nodeId() + "**")
                    .append(": ").append(oneLine(describe(e))).append('\n');
        }
    }

    private static String describe(Event e) {
        return switch (e.type()) {
            case CLARIFICATION_RECORDED -> "from " + e.get("from") + ("true".equals(e.get("scripted"))
                    ? " (scripted stakeholder)" : "") + ": " + e.get("text");
            case APPROVAL_GRANTED -> "by " + e.get("approver") + " for output `" + e.get("digest").substring(0, 12) + "`";
            case APPROVAL_REJECTED -> "by " + e.get("approver") + ": " + e.get("feedback");
            case APPROVAL_INVALIDATED -> e.get("reason");
            case NODE_ATTEMPT_FAILED -> "attempt " + e.get("attempt") + ": " + e.get("reason");
            case DECISION_RECORDED -> e.get("title") + ": " + e.get("choice") + " (" + e.get("rationale") + ")";
            case COMPENSATION_EXECUTED -> e.get("detail") + " (" + e.get("reason") + ")";
            default -> e.data().containsKey("reason") ? e.get("reason") : e.data().toString();
        };
    }

    private static boolean isGate(Event e, String nodeId) {
        return e.type() == EventType.GATE_EVALUATED && e.nodeId().equals(nodeId);
    }

    private static String oneLine(String s) {
        String flat = s == null ? "" : s.replace('\n', ' ').replace("|", "\\|").strip();
        return flat.length() > 400 ? flat.substring(0, 400) + "..." : flat;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void write(Path p, String content) {
        try {
            Files.writeString(p, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
