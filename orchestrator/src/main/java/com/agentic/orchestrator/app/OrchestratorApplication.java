package com.agentic.orchestrator.app;

import com.agentic.orchestrator.demo.FakeDagDemo;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Spring is only the edge: wiring, configuration and (from Day 2) the approval console. The engine
 * itself has no Spring dependency.
 */
@SpringBootApplication
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }

    @Bean
    CommandLineRunner cli() {
        return args -> {
            String command = args.length == 0 ? "help" : args[0];
            switch (command) {
                case "fake-demo" -> new FakeDagDemo(System.out).runAll();
                default -> System.out.println("""
                        usage: orchestrator <command>
                          fake-demo   Day-1 engine demo: 8-node DAG with fake agents (no LLM, no API key)
                        """);
            }
        };
    }
}
