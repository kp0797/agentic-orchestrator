package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.artifacts.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Versioned prompt templates from {@code prompts/<name>.md}. The rendered prompt's hash is stored on
 * every artifact the agent produces, so a prompt change is visible in lineage and forces a re-record.
 */
public final class PromptLibrary {
    public record Template(String name, String version, String system, String user) {
        public String id() {
            return name + "@" + version;
        }
    }

    public record Rendered(Template template, String system, String user) {
        public String hash() {
            return Hashing.sha256(system + "\u0000" + user);
        }
    }

    private final Map<String, Template> cache = new ConcurrentHashMap<>();

    public Template load(String name) {
        return cache.computeIfAbsent(name, n -> {
            try (InputStream in = PromptLibrary.class.getResourceAsStream("/prompts/" + n + ".md")) {
                if (in == null) {
                    throw new IllegalArgumentException("no prompt template " + n);
                }
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                String version = text.lines().findFirst().orElse("").replace("version:", "").strip();
                int sys = text.indexOf("---SYSTEM---");
                int usr = text.indexOf("---USER---");
                if (version.isEmpty() || sys < 0 || usr < sys) {
                    throw new IllegalArgumentException("prompt " + n + " needs 'version:', ---SYSTEM--- and ---USER---");
                }
                return new Template(n, version, text.substring(sys + 12, usr).strip(), text.substring(usr + 10).strip());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    public Rendered render(String name, Map<String, String> vars) {
        Template t = load(name);
        return new Rendered(t, fill(t.system(), vars), fill(t.user(), vars));
    }

    static String fill(String text, Map<String, String> vars) {
        // Check the template's own placeholders first: filled-in values (source code, specs) may
        // legitimately contain "{{" and must not be mistaken for missing variables.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{([a-zA-Z]+)}}").matcher(text);
        while (m.find()) {
            if (!vars.containsKey(m.group(1))) {
                throw new IllegalArgumentException("prompt variable '" + m.group(1) + "' was not provided");
            }
        }
        // Single pass, so a value containing "{{x}}" is never expanded a second time.
        m.reset();
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(v == null ? "" : v));
        }
        m.appendTail(out);
        return out.toString();
    }
}
