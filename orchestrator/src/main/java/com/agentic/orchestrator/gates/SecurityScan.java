package com.agentic.orchestrator.gates;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * In-process static checks over the integrated source tree. It is deliberately small and honest:
 * a handful of high-signal rules. Dedicated scanners (Semgrep, gitleaks, a dependency CVE scan) are not
 * part of this project yet.
 * Report lines are {@code SEVERITY|rule|file:line|detail}.
 */
public final class SecurityScan {
    private SecurityScan() {}

    record Rule(String id, String severity, Pattern pattern, String detail) {}

    static final List<Rule> RULES = List.of(
            new Rule("hardcoded-secret", "HIGH",
                    Pattern.compile("(?i)(password|secret|api[_-]?key|token)\\s*=\\s*\"[^\"]{8,}\""),
                    "credential literal in source"),
            new Rule("anthropic-key", "HIGH", Pattern.compile("sk-ant-[A-Za-z0-9_-]{20,}"), "API key in source"),
            new Rule("openai-style-key", "HIGH", Pattern.compile("(?<![A-Za-z0-9_-])sk-(?!ant-)[A-Za-z0-9_-]{32,}"),
                    "API key in source"),
            new Rule("private-key", "HIGH", Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
                    "private key in source"),
            new Rule("command-exec", "HIGH", Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec|new ProcessBuilder\\("),
                    "runs OS commands"),
            new Rule("java-deserialization", "HIGH", Pattern.compile("new ObjectInputStream\\("),
                    "native Java deserialization"),
            new Rule("sql-concatenation", "HIGH",
                    Pattern.compile("(createNativeQuery|createQuery|executeQuery|prepareStatement)\\(\\s*\"[^\"]*\"\\s*\\+"),
                    "SQL built by string concatenation"),
            new Rule("cors-wildcard", "MEDIUM", Pattern.compile("@CrossOrigin\\(\\s*(origins\\s*=\\s*)?\"\\*\""),
                    "CORS open to every origin"),
            new Rule("logs-raw-ip", "MEDIUM", Pattern.compile("(?i)log\\.[a-z]+\\(.*getRemoteAddr\\(\\)"),
                    "raw client IP written to logs"));

    /** Scans {@code src/main} under the worktree and returns report lines. */
    public static List<String> scan(Path worktree) {
        List<String> findings = new ArrayList<>();
        Path src = worktree.resolve("src/main");
        if (!Files.isDirectory(src)) {
            return findings;
        }
        try (var files = Files.walk(src)) {
            for (Path f : files.filter(Files::isRegularFile).filter(p -> {
                String n = p.toString();
                return n.endsWith(".java") || n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".properties")
                        || n.endsWith(".sql");
            }).sorted().toList()) {
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    for (Rule r : RULES) {
                        if (r.pattern().matcher(lines.get(i)).find()) {
                            findings.add(r.severity() + "|" + r.id() + "|" + worktree.relativize(f) + ":" + (i + 1)
                                    + "|" + r.detail());
                        }
                    }
                }
            }
        } catch (java.io.IOException e) {
            findings.add("HIGH|scan-error|" + src + "|" + e.getMessage());
        }
        return findings;
    }

    /** Fails on any HIGH finding; MEDIUM findings are reported but escalate to a human instead. */
    public static final class Gate implements com.agentic.orchestrator.gates.Gate {
        public static final String ID = "security-clean";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public GateResult evaluate(GateContext ctx) {
            var report = ctx.outputs().stream().filter(o -> o.name().equals("security-report.txt")).findFirst();
            if (report.isEmpty()) {
                return GateResult.fail(ID, "security-report.txt missing");
            }
            List<String> lines = report.get().contentAsString().lines().filter(l -> !l.isBlank()).toList();
            List<String> high = lines.stream().filter(l -> l.startsWith("HIGH|")).toList();
            List<String> medium = lines.stream().filter(l -> l.startsWith("MEDIUM|")).toList();
            if (!high.isEmpty()) {
                return GateResult.fail(ID, high.size() + " high-severity finding(s): " + high);
            }
            if (!medium.isEmpty()) {
                return GateResult.escalate(ID, medium.size() + " medium finding(s)", "security findings to review: "
                        + medium);
            }
            return GateResult.pass(ID, "no findings across " + RULES.size() + " rules");
        }
    }
}
