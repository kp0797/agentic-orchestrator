package com.agentic.orchestrator.gates;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Lightweight in-process secret check on text outputs. The real build adds gitleaks on the
 * workspace; this gate covers artifacts that never touch the file system (specs, plans, docs).
 */
public final class NoSecretsGate implements Gate {
    public static final String ID = "no-secrets";

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Pattern.compile("(?i)(password|secret|api[_-]?key)\\s*[:=]\\s*['\"][^'\"]{8,}['\"]"));

    @Override
    public String id() {
        return ID;
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        for (var out : ctx.outputs()) {
            String text = out.contentAsString();
            for (Pattern p : PATTERNS) {
                if (p.matcher(text).find()) {
                    return GateResult.fail(ID, "possible secret in '" + out.name() + "' (pattern " + p.pattern() + ")");
                }
            }
        }
        return GateResult.pass(ID, "no secret patterns found");
    }
}
