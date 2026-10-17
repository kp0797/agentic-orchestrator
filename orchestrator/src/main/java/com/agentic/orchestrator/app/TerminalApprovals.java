package com.agentic.orchestrator.app;

import com.agentic.orchestrator.artifacts.ArtifactStore;
import com.agentic.orchestrator.engine.ApprovalDecision;
import com.agentic.orchestrator.engine.ApprovalHandler;
import com.agentic.orchestrator.engine.ApprovalRequest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Supplier;

/** Asks for each approval in the terminal, showing what is being approved and why. */
final class TerminalApprovals implements ApprovalHandler {
    private final Supplier<ArtifactStore> artifacts;
    private final PrintStream out;
    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    TerminalApprovals(Supplier<ArtifactStore> artifacts, PrintStream out) {
        this.artifacts = artifacts;
        this.out = out;
    }

    @Override
    public synchronized Optional<ApprovalDecision> onApprovalRequested(ApprovalRequest r) {
        out.println();
        out.println("=== Approval needed: " + r.node().id() + " (" + r.node().type() + ") in run " + r.runId());
        out.println("Why: " + r.reason());
        for (String hash : r.artifactHashes()) {
            artifacts.get().get(hash).ifPresent(a -> {
                if (a.name().endsWith(".md") || a.name().equals("plan.json") || a.name().endsWith(".txt")) {
                    String text = a.contentAsString();
                    out.println("--- " + a.name() + " ---");
                    out.println(text.length() > 4000 ? text.substring(0, 4000) + "\n... (truncated)" : text);
                }
            });
        }
        out.println("Output digest: " + r.outputDigest());
        try {
            out.print("Approve? [y = approve / n = reject with feedback / s = decide later in the console]: ");
            out.flush();
            String answer = in.readLine();
            if (answer == null || answer.isBlank() || answer.strip().startsWith("s")) {
                return Optional.empty();
            }
            out.print("Your name: ");
            out.flush();
            String name = Optional.ofNullable(in.readLine()).filter(s -> !s.isBlank()).orElse("terminal-user");
            if (answer.strip().toLowerCase().startsWith("y")) {
                return Optional.of(ApprovalDecision.approve(name, r.outputDigest(), "approved in terminal"));
            }
            out.print("Feedback for the agent: ");
            out.flush();
            String feedback = Optional.ofNullable(in.readLine()).orElse("");
            return Optional.of(ApprovalDecision.reject(name, r.outputDigest(), feedback));
        } catch (java.io.IOException e) {
            return Optional.empty();
        }
    }
}
