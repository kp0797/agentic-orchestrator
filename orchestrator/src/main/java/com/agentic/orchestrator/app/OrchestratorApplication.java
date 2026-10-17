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
                default -> System.out.println("""
                        usage: orchestrator <command>
                          serve                       approval console on :8080
                          run <scenario> [--auto-approve]
                                                      run a scenario in the terminal; approvals are asked here,
                                                      or granted automatically and labelled as such
                          scenarios                   list scenarios
                          fake-demo                   engine demo with fake agents (no LLM)
                        mode (replay | live | record) comes from agentic.mode / AGENTIC_MODE
                        """);
            }
        };
    }

    private static void runScenario(RunService runs, List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("run needs a scenario name; see 'scenarios'");
        }
        boolean auto = args.contains("--auto-approve");
        Orchestrator[] holder = new Orchestrator[1];
        ApprovalHandler approvals = auto ? ApprovalHandler.autoApprove("cli --auto-approve")
                : new TerminalApprovals(() -> holder[0].artifacts(), System.out);
        Orchestrator o = runs.create(args.get(0), approvals);
        holder[0] = o;
        System.out.println("Scenario " + o.scenario().name() + " in " + runs.properties().mode() + " mode"
                + (auto ? " (approvals automatic and labelled in the audit log)" : ""));
        RunOutcome outcome = o.drive();
        for (String runId : List.of(o.planningRunId().orElse(""), o.executionRunId().orElse(""))) {
            if (runId.isEmpty()) continue;
            System.out.println("\n--- run " + runId + " ---");
            EventPrinter.print(runs.events().events(runId), System.out, false);
            var audit = AuditVerifier.verify(runs.events().events(runId));
            System.out.println("Audit chain: " + (audit.valid() ? "VALID" : "BROKEN") + ", head "
                    + (audit.headHash() == null ? "-" : audit.headHash().substring(0, 16)));
            System.out.println(RunMetrics.from(runs.events().events(runId)).summary());
        }
        System.out.println("\nResult: " + outcome.status() + (outcome.reason().isEmpty() ? "" : " (" + outcome.reason() + ")"));
        o.planningRunId().ifPresent(id -> System.out.println("Workspace: " + runs.properties().runsDir() + "/" + id));
    }
}
