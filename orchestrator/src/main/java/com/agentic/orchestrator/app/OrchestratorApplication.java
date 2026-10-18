package com.agentic.orchestrator.app;

import com.agentic.orchestrator.demo.EventPrinter;
import com.agentic.orchestrator.demo.FakeDagDemo;
import com.agentic.orchestrator.engine.ApprovalHandler;
import com.agentic.orchestrator.engine.RunOutcome;
import com.agentic.orchestrator.events.AuditVerifier;
import com.agentic.orchestrator.metrics.RunMetrics;
import com.agentic.orchestrator.scenario.Orchestrator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;
import java.util.List;

/**
 * Spring is only the edge: configuration, the approval console and the CLI. The engine itself has
 * no Spring dependency.
 */
@SpringBootApplication(scanBasePackages = {"com.agentic.orchestrator.app", "com.agentic.orchestrator.web"})
@EnableConfigurationProperties(AgenticProperties.class)
public class OrchestratorApplication {

    public static void main(String[] args) {
        boolean serve = args.length > 0 && args[0].equals("serve");
        new SpringApplicationBuilder(OrchestratorApplication.class)
                .web(serve ? WebApplicationType.SERVLET : WebApplicationType.NONE)
                .run(args);
    }

    @Bean(destroyMethod = "close")
    RunService runService(AgenticProperties props) {
        return new RunService(props);
    }

    @Bean
    CommandLineRunner cli(RunService runs) {
        return args -> {
            String command = args.length == 0 ? "help" : args[0];
            List<String> rest = Arrays.asList(args).subList(Math.min(1, args.length), args.length);
            switch (command) {
                case "serve" -> System.out.println("Approval console on http://localhost:8080 (mode "
                        + runs.properties().mode() + ")");
                case "fake-demo" -> new FakeDagDemo(System.out).runAll();
                case "scenarios" -> runs.scenarios().forEach(s -> System.out.println(s.name() + "  " + s.description()));
                case "run" -> runScenario(runs, rest);
                case "eval" -> eval(runs, rest);
                case "verify-evidence" -> verifyEvidence(rest);
                case "ping-model" -> pingModel(runs);
                default -> System.out.println("""
                        usage: orchestrator <command>
                          serve                       approval console on :8080
                          run <scenario> [--auto-approve] [--inject <fault>]...
                                                      run a scenario in the terminal; approvals are asked here,
                                                      or granted automatically and labelled as such.
                                                      Faults: node:exception[@1,2] or node:gate:<gate-id>[@1]
                          eval <scenario>[,<scenario>...] [--runs N] [--inject <fault>]...
                                                      run each scenario N times unattended; writes
                                                      runs/eval-<time>/eval-summary.md and an evidence pack per run
                          verify-evidence <dir>       re-check the audit hash chains in an evidence pack
                          scenarios                   list scenarios
                          ping-model                  one tiny live call (cheapest model) to check the API key
                          fake-demo                   engine demo with fake agents (no LLM)
                        mode (replay | live | record) comes from agentic.mode / AGENTIC_MODE
                        """);
            }
        };
    }

    /** Checks the key and the live gateway with the cheapest model before any expensive run. */
    private static void pingModel(RunService runs) {
        String model = runs.modelSettings().forAgent("docs").model();
        var gateway = new com.agentic.orchestrator.model.live.SpringAiModelGateway(System.getenv("ANTHROPIC_API_KEY"));
        var response = gateway.complete(new com.agentic.orchestrator.model.ModelRequest(model,
                "Reply with exactly the word OK.", "Health check.", java.util.Map.of("maxTokens", "10")));
        double cost = runs.modelSettings().cost(response.model(), response.inputTokens(), response.outputTokens());
        System.out.printf("Model %s replied '%s' (%d input + %d output tokens, about $%.6f). The key works.%n",
                response.model(), response.text().strip(), response.inputTokens(), response.outputTokens(), cost);
    }

    private static void runScenario(RunService runs, List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("run needs a scenario name; see 'scenarios'");
        }
        boolean auto = args.contains("--auto-approve");
        List<com.agentic.orchestrator.engine.FaultSpec> faults = new java.util.ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("--inject")) {
                if (i + 1 >= args.size()) throw new IllegalArgumentException("--inject needs a fault");
                faults.add(com.agentic.orchestrator.engine.FaultSpec.parse(args.get(++i)));
            }
        }
        Orchestrator[] holder = new Orchestrator[1];
        ApprovalHandler approvals = auto ? ApprovalHandler.autoApprove("cli --auto-approve")
                : new TerminalApprovals(() -> holder[0].artifacts(), System.out);
        // Unattended (--auto-approve): the scenario's scripted stakeholder answers. Interactive: you do.
        Orchestrator o = runs.create(args.get(0), approvals, faults, auto);
        holder[0] = o;
        System.out.println("Scenario " + o.scenario().name() + " in " + runs.properties().mode() + " mode"
                + (auto ? " (approvals automatic and labelled in the audit log)" : "")
                + (faults.isEmpty() ? "" : "; injected faults: " + faults.size()));
        RunOutcome outcome = o.drive();
        List<String> shown = new java.util.ArrayList<>();
        o.planningRunId().ifPresent(shown::add);
        shown.addAll(o.supersededRuns());
        o.executionRunId().ifPresent(shown::add);
        for (String runId : shown) {
            String role = o.planningRunId().filter(runId::equals).isPresent() ? "planning"
                    : o.supersededRuns().contains(runId) ? "execution, superseded by a re-plan" : "execution";
            System.out.println("\n--- run " + runId + " (" + role + ", plan v"
                    + o.plans().load(runId).plan().version() + ") ---");
            EventPrinter.print(runs.events().events(runId), System.out, false);
            var audit = AuditVerifier.verify(runs.events().events(runId));
            System.out.println("Audit chain: " + (audit.valid() ? "VALID" : "BROKEN") + ", head "
                    + (audit.headHash() == null ? "-" : audit.headHash().substring(0, 16)));
            System.out.println(RunMetrics.from(runs.events().events(runId)).summary());
        }
        System.out.println("\nResult: " + outcome.status() + (outcome.reason().isEmpty() ? "" : " (" + outcome.reason() + ")"));
        o.planningRunId().ifPresent(id -> {
            System.out.println("Workspace: " + runs.properties().runsDir() + "/" + id);
            java.nio.file.Path evidence = com.agentic.orchestrator.evidence.EvidencePack.write(o,
                    java.nio.file.Path.of(runs.properties().runsDir(), id, "evidence"),
                    runs.properties().mode().name().toLowerCase());
            System.out.println("Evidence: " + evidence + "/README.md");
        });
    }

    private static void eval(RunService runs, List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("eval needs scenario names, e.g. eval pipeline-smoke,brownfield --runs 3");
        }
        List<String> scenarios = List.of(args.get(0).split(","));
        int n = 3;
        List<com.agentic.orchestrator.engine.FaultSpec> faults = new java.util.ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--runs" -> n = Integer.parseInt(args.get(++i));
                case "--inject" -> faults.add(com.agentic.orchestrator.engine.FaultSpec.parse(args.get(++i)));
                default -> { /* Spring properties such as --agentic.runs-dir=... */ }
            }
        }
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
        java.nio.file.Path outDir = java.nio.file.Path.of(runs.properties().runsDir(), "eval-" + stamp);
        String mode = runs.properties().mode().name().toLowerCase();
        var harness = new com.agentic.orchestrator.eval.EvalHarness(name -> runs.create(name,
                ApprovalHandler.autoApprove("eval-harness"), faults, true), mode, System.out);
        var report = harness.run(scenarios, n, outDir);
        System.out.println();
        System.out.println(report.markdown());
        System.out.println("Written to " + outDir + "/eval-summary.md and eval-summary.json");
    }

    private static void verifyEvidence(List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("verify-evidence needs an evidence directory");
        }
        var results = com.agentic.orchestrator.evidence.AuditFile.verifyDirectory(java.nio.file.Path.of(args.get(0)));
        boolean allValid = true;
        for (var e : results.entrySet()) {
            var r = e.getValue();
            allValid &= r.valid();
            System.out.println((r.valid() ? "VALID   " : "BROKEN  ") + e.getKey() + "  " + r.checked() + " events"
                    + (r.valid() ? ", head " + r.headHash().substring(0, 16) : ", first bad event " + r.firstBadSeq()
                    + ": " + r.reason()));
        }
        System.out.println(allValid ? "All audit chains verify." : "Tampering or corruption detected.");
        if (!allValid) {
            System.exit(1);
        }
    }
}
