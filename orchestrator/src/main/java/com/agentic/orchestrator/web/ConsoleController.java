package com.agentic.orchestrator.web;

import com.agentic.orchestrator.app.RunService;
import com.agentic.orchestrator.artifacts.Artifact;
import com.agentic.orchestrator.demo.EventPrinter;
import com.agentic.orchestrator.domain.NodeStatus;
import com.agentic.orchestrator.domain.Plan;
import com.agentic.orchestrator.engine.ApprovalDecision;
import com.agentic.orchestrator.engine.ApprovalMismatchException;
import com.agentic.orchestrator.engine.NodeRuntime;
import com.agentic.orchestrator.engine.RunState;
import com.agentic.orchestrator.events.AuditVerifier;
import com.agentic.orchestrator.events.Event;
import com.agentic.orchestrator.events.EventType;
import com.agentic.orchestrator.metrics.RunMetrics;
import com.agentic.orchestrator.scenario.Orchestrator;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The human side of controlled autonomy: see what agents did, why a decision is needed, the exact
 * diff and gate results, then approve or reject. Decisions are bound to the output digest shown.
 * Approver names are typed in, not authenticated (documented limitation).
 */
@Controller
public class ConsoleController {
    private final RunService runs;

    public ConsoleController(RunService runs) {
        this.runs = runs;
    }

    public record ScenarioRow(String name, String description, String template, boolean hasCassette) {}

    public record RunRow(String scenario, String planningRun, String planningStatus, String executionRun,
                         String executionStatus, List<String> awaiting) {}

    public record NodeRow(String id, String type, String status, int attempts, String executor, String risk,
                          boolean awaiting, String lastFailure) {}

    public record EventRow(long seq, long offsetMs, String node, String type, String detail, String css) {}

    public record ArtifactView(String name, String hash, String content, boolean truncated) {}

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("mode", runs.properties().mode());
        model.addAttribute("scenarios", runs.scenarios().stream().map(s -> new ScenarioRow(s.name(), s.description(),
                s.template().getFileName().toString(), java.nio.file.Files.exists(s.cassette()))).toList());
        List<RunRow> rows = new ArrayList<>();
        for (Orchestrator o : runs.orchestrators()) {
            if (o.planningRunId().isEmpty()) continue;
            String p = o.planningRunId().get();
            String e = o.executionRunId().orElse(null);
            List<String> awaiting = new ArrayList<>(awaiting(o, p));
            if (e != null) awaiting.addAll(awaiting(o, e));
            rows.add(new RunRow(o.scenario().name(), p, status(o, p), e, e == null ? "-" : status(o, e), awaiting));
        }
        model.addAttribute("runs", rows);
        model.addAttribute("errors", runs.errors());
        return "index";
    }

    @PostMapping("/scenarios/{name}/start")
    public String start(@PathVariable String name, RedirectAttributes flash) {
        try {
            return "redirect:/runs/" + runs.start(name);
        } catch (RuntimeException e) {
            flash.addFlashAttribute("flash", "Could not start '" + name + "': " + e.getMessage());
            return "redirect:/";
        }
    }

    @GetMapping("/runs/{runId}")
    public String run(@PathVariable String runId, Model model) {
        Orchestrator o = runs.orchestrator(runId);
        RunState state = o.engine().state(runId);
        Plan plan = state.plan();
        List<NodeRow> nodes = plan.topologicalOrder().stream().map(id -> {
            NodeRuntime rt = state.node(id);
            return new NodeRow(id, plan.node(id).type().name(), rt.status().name(), rt.attempts(),
                    Optional.ofNullable(rt.currentExecutor()).orElse(plan.node(id).executorKey()),
                    Optional.ofNullable(rt.effectiveRisk()).orElse(plan.node(id).risk().name()),
                    rt.status() == NodeStatus.AWAITING_APPROVAL, rt.lastFailure());
        }).toList();
        List<Event> log = runs.events().events(runId);
        model.addAttribute("runId", runId);
        model.addAttribute("scenario", o.scenario().name());
        model.addAttribute("stage", o.planningRunId().filter(runId::equals).isPresent() ? "planning" : "execution");
        model.addAttribute("otherRun", o.planningRunId().filter(runId::equals).isPresent()
                ? o.executionRunId().orElse(null) : o.planningRunId().orElse(null));
        model.addAttribute("status", state.status().name());
        model.addAttribute("reason", state.statusReason());
        model.addAttribute("nodes", nodes);
        model.addAttribute("events", timeline(log));
        model.addAttribute("metrics", log.isEmpty() ? "" : RunMetrics.from(log).summary());
        AuditVerifier.Result audit = AuditVerifier.verify(log);
        model.addAttribute("auditValid", audit.valid());
        model.addAttribute("auditDetail", audit.valid() ? audit.checked() + " events, head " + audit.headHash()
                : "broken at event " + audit.firstBadSeq() + ": " + audit.reason());
        model.addAttribute("live", state.status().name().equals("RUNNING"));
        return "run";
    }

    @GetMapping("/runs/{runId}/nodes/{nodeId}")
    public String node(@PathVariable String runId, @PathVariable String nodeId, Model model) {
        Orchestrator o = runs.orchestrator(runId);
        RunState state = o.engine().state(runId);
        NodeRuntime rt = state.node(nodeId);
        List<Event> nodeEvents = runs.events().events(runId).stream().filter(e -> e.nodeId().equals(nodeId)).toList();
        int attempt = rt.attempts();
        model.addAttribute("runId", runId);
        model.addAttribute("node", state.plan().node(nodeId));
        model.addAttribute("rt", rt);
        model.addAttribute("policy", nodeEvents.stream().filter(e -> e.type() == EventType.POLICY_EVALUATED)
                .reduce((a, b) -> b).map(e -> e.get("reason")).orElse("not evaluated yet"));
        model.addAttribute("gates", nodeEvents.stream().filter(e -> e.type() == EventType.GATE_EVALUATED
                && String.valueOf(attempt).equals(e.get("attempt"))).map(e -> new EventRow(e.seq(), 0, e.get("gate"),
                "true".equals(e.get("passed")) ? (e.get("escalation") != null ? "ESCALATED" : "PASS") : "FAIL",
                e.get("detail") + (e.get("escalation") != null ? " | needs a human: " + e.get("escalation") : ""),
                "true".equals(e.get("passed")) ? (e.get("escalation") != null ? "warn" : "ok") : "bad")).toList());
        model.addAttribute("failures", nodeEvents.stream().filter(e -> e.type() == EventType.NODE_ATTEMPT_FAILED)
                .map(e -> "attempt " + e.get("attempt") + ": " + e.get("reason")).toList());
        model.addAttribute("approvalReason", nodeEvents.stream().filter(e -> e.type() == EventType.APPROVAL_REQUESTED)
                .reduce((a, b) -> b).map(e -> e.get("reason")).orElse(""));
        List<ArtifactView> artifacts = new ArrayList<>();
        for (String hash : rt.outputHashes()) {
            o.artifacts().get(hash).ifPresent(a -> artifacts.add(view(a)));
        }
        artifacts.sort(Comparator.comparing((ArtifactView a) -> !a.name().endsWith(".md")).thenComparing(ArtifactView::name));
        model.addAttribute("artifacts", artifacts);
        model.addAttribute("awaiting", rt.status() == NodeStatus.AWAITING_APPROVAL);
        return "node";
    }

    @PostMapping("/runs/{runId}/nodes/{nodeId}/decision")
    public String decide(@PathVariable String runId, @PathVariable String nodeId, @RequestParam String decision,
                         @RequestParam String approver, @RequestParam(defaultValue = "") String comment,
                         @RequestParam String digest, RedirectAttributes flash) {
        if (approver.isBlank()) {
            flash.addFlashAttribute("flash", "Enter your name: every decision is attributed in the audit log.");
            return "redirect:/runs/" + runId + "/nodes/" + nodeId;
        }
        if (decision.equals("reject") && comment.isBlank()) {
            flash.addFlashAttribute("flash", "A rejection needs feedback: it is what the agent gets for its retry.");
            return "redirect:/runs/" + runId + "/nodes/" + nodeId;
        }
        try {
            runs.decide(runId, nodeId, decision.equals("approve")
                    ? ApprovalDecision.approve(approver.strip(), digest, comment)
                    : ApprovalDecision.reject(approver.strip(), digest, comment));
            flash.addFlashAttribute("flash", (decision.equals("approve") ? "Approved " : "Rejected ") + nodeId
                    + "; the run continues.");
        } catch (ApprovalMismatchException e) {
            flash.addFlashAttribute("flash", "Not recorded: the output changed since this page loaded. Review it again.");
        }
        return "redirect:/runs/" + runId;
    }

    @PostMapping("/runs/{runId}/stop")
    public String stop(@PathVariable String runId, RedirectAttributes flash) {
        runs.stop(runId, "kill switch pressed in the console");
        flash.addFlashAttribute("flash", "Safe-stop requested. In-flight work is cancelled; the run can be resumed.");
        return "redirect:/runs/" + runId;
    }

    @PostMapping("/runs/{runId}/resume")
    public String resume(@PathVariable String runId) {
        runs.resume(runId);
        return "redirect:/runs/" + runId;
    }

    private String status(Orchestrator o, String runId) {
        return o.engine().state(runId).status().name();
    }

    private List<String> awaiting(Orchestrator o, String runId) {
        RunState s = o.engine().state(runId);
        return s.plan().topologicalOrder().stream()
                .filter(id -> s.node(id).status() == NodeStatus.AWAITING_APPROVAL)
                .map(id -> runId + "/" + id).toList();
    }

    private static List<EventRow> timeline(List<Event> log) {
        if (log.isEmpty()) return List.of();
        var t0 = log.get(0).timestamp();
        return log.stream().filter(e -> e.type() != EventType.TOKENS_USED && e.type() != EventType.ARTIFACT_PRODUCED)
                .map(e -> new EventRow(e.seq(), Duration.between(t0, e.timestamp()).toMillis(),
                        e.nodeId().isEmpty() ? "-" : e.nodeId(), e.type().name(), EventPrinter.describe(e), css(e)))
                .toList();
    }

    private static String css(Event e) {
        return switch (e.type()) {
            case NODE_ATTEMPT_FAILED, RUN_FAILED, RUN_STOPPED, BUDGET_EXCEEDED, COMPENSATION_FAILED -> "bad";
            case APPROVAL_REQUESTED, FAULT_INJECTED, FALLBACK_ACTIVATED, APPROVAL_INVALIDATED, RUN_PAUSED -> "warn";
            case APPROVAL_GRANTED, RUN_SUCCEEDED -> "ok";
            case GATE_EVALUATED -> "true".equals(e.get("passed")) ? "" : "bad";
            default -> "";
        };
    }

    private static ArtifactView view(Artifact a) {
        String text = a.contentAsString();
        boolean truncated = text.length() > 30_000;
        return new ArtifactView(a.name(), a.hash(), truncated ? text.substring(0, 30_000) : text, truncated);
    }
}
