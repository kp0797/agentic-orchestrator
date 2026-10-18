package com.agentic.orchestrator.scenario;

import com.agentic.orchestrator.model.ModelGateway;
import com.agentic.orchestrator.model.ModelRequest;
import com.agentic.orchestrator.model.ModelResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Plays each agent with canned answers so the whole pipeline runs offline and deterministically.
 * Answers are chosen by role (read from the system prompt) and by how many times that role was called.
 */
final class ScriptedModel implements ModelGateway {
    final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    final List<ModelRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Integer, String>> answers;

    ScriptedModel(Map<String, Function<Integer, String>> answers) {
        this.answers = answers;
    }

    static String role(ModelRequest r) {
        String s = r.system();
        if (s.contains("requirements analyst")) return "requirements";
        if (s.contains("You are the planner")) return "planner";
        if (s.contains("You are the architect agent")) return "architect";
        if (s.contains("You are the coder agent")) return "coder";
        if (s.contains("You are the tester agent")) return "tester";
        if (s.contains("You are the documentation agent")) return "docs";
        throw new IllegalStateException("unknown role in system prompt");
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        requests.add(request);
        String role = role(request);
        // Answers can be given per node ("coder:fix-alias-race"): parallel nodes of one role then get
        // their own answers whatever order they run in.
        String perNode = role + ":" + nodeId(request);
        String key = answers.containsKey(perNode) ? perNode : role;
        int n = calls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        if (!key.equals(role)) {
            calls.computeIfAbsent(role, k -> new AtomicInteger()).incrementAndGet();
        }
        String text = answers.get(key).apply(n);
        return new ModelResponse(text, request.model(), request.prompt().length() / 4, text.length() / 4);
    }

    static String nodeId(ModelRequest r) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^Task: ([a-z0-9-]+) \\(").matcher(r.prompt());
        return m.find() ? m.group(1) : "";
    }

    int count(String role) {
        return calls.getOrDefault(role, new AtomicInteger()).get();
    }

    // ---- canned answers for a tiny Base62 codec -------------------------------------------------

    static final String SPEC = """
            {"title": "Base62 codec", "summary": "Encode ids as short Base62 strings and back.",
             "goals": ["short codes"], "nonFunctional": ["pure function, no I/O"],
             "acceptanceCriteria": [
               {"id": "AC1", "given": "id 0", "when": "encoded", "then": "the code is \\"0\\""},
               {"id": "AC2", "given": "any non-negative id", "when": "encoded then decoded", "then": "the id is returned"}],
             "ambiguities": [{"question": "Alphabet order?", "proposedDefault": "0-9a-zA-Z", "confidence": "HIGH"}],
             "assumptions": ["ids fit in a long"], "outOfScope": ["negative ids"]}
            """;

    static final String PLAN = """
            Here is the plan:
            {"rationale": "small library: one implementation and one test task in parallel",
             "nodes": [
              {"id": "design", "type": "DESIGN", "description": "Codec interface", "dependsOn": [], "risk": "LOW",
               "requiresApproval": false, "acceptanceCriteria": ["AC1", "AC2"]},
              {"id": "implement", "type": "IMPLEMENT", "description": "Base62 implementation", "dependsOn": ["design"],
               "risk": "LOW", "requiresApproval": false, "acceptanceCriteria": ["AC1", "AC2"]},
              {"id": "write-tests", "type": "TEST", "description": "Tests for AC1 and AC2", "dependsOn": ["design"],
               "risk": "LOW", "requiresApproval": false, "acceptanceCriteria": ["AC1", "AC2"]},
              {"id": "integrate", "type": "JOIN", "description": "merge and test", "dependsOn": ["implement", "write-tests"],
               "risk": "LOW", "requiresApproval": false, "acceptanceCriteria": []},
              {"id": "docs", "type": "DOCS", "description": "README", "dependsOn": ["integrate"], "risk": "LOW",
               "requiresApproval": false, "acceptanceCriteria": []},
              {"id": "security-scan", "type": "SECURITY_SCAN", "description": "scan", "dependsOn": ["integrate"],
               "risk": "LOW", "requiresApproval": false, "acceptanceCriteria": []},
              {"id": "release-readiness", "type": "RELEASE_READINESS", "description": "release",
               "dependsOn": ["docs", "security-scan"], "risk": "HIGH", "requiresApproval": true, "acceptanceCriteria": []}]}
            """;

    static final String INVALID_PLAN = """
            {"rationale": "tests after code", "nodes": [
              {"id": "design", "type": "DESIGN", "dependsOn": [], "acceptanceCriteria": ["AC1", "AC2"]},
              {"id": "implement", "type": "IMPLEMENT", "dependsOn": ["design"], "acceptanceCriteria": ["AC1", "AC2"]},
              {"id": "write-tests", "type": "TEST", "dependsOn": ["implement"], "acceptanceCriteria": ["AC1", "AC2"]},
              {"id": "release-readiness", "type": "RELEASE_READINESS", "dependsOn": ["write-tests"]}]}
            """;

    static final String DESIGN = """
            {"summary": "Codec contract; implementation class is com.example.codec.core.Base62Codec (public, no-arg).",
             "edits": [
              {"path": "src/main/java/com/example/codec/api/Codec.java", "action": "create",
               "content": "package com.example.codec.api;\\n\\n/** Encodes non-negative ids as Base62 and back. */\\npublic interface Codec {\\n    String encode(long id);\\n\\n    long decode(String code);\\n}\\n"},
              {"path": "docs/design.md", "action": "create",
               "content": "# Design\\n\\nImplementation: `com.example.codec.core.Base62Codec`, public no-arg constructor.\\nAlphabet: 0-9a-zA-Z.\\n"}],
             "decisions": [{"title": "Alphabet", "context": "AC1", "options": ["0-9a-zA-Z", "a-zA-Z0-9"],
                            "choice": "0-9a-zA-Z", "rationale": "0 encodes as \\"0\\" (AC1)"}]}
            """;

    static String impl(boolean correct) {
        String alphabet = correct ? "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                : "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        String code = "package com.example.codec.core;\\n\\nimport com.example.codec.api.Codec;\\n\\n"
                + "public final class Base62Codec implements Codec {\\n"
                + "    private static final String ALPHABET = \\\"" + alphabet + "\\\";\\n\\n"
                + "    @Override\\n    public String encode(long id) {\\n"
                + "        if (id < 0) throw new IllegalArgumentException(\\\"negative id\\\");\\n"
                + "        if (id == 0) return String.valueOf(ALPHABET.charAt(0));\\n"
                + "        StringBuilder sb = new StringBuilder();\\n"
                + "        while (id > 0) { sb.append(ALPHABET.charAt((int) (id % 62))); id /= 62; }\\n"
                + "        return sb.reverse().toString();\\n    }\\n\\n"
                + "    @Override\\n    public long decode(String code) {\\n"
                + "        long id = 0;\\n"
                + "        for (char c : code.toCharArray()) {\\n"
                + "            int v = ALPHABET.indexOf(c);\\n"
                + "            if (v < 0) throw new IllegalArgumentException(\\\"bad char\\\");\\n"
                + "            id = id * 62 + v;\\n        }\\n        return id;\\n    }\\n}\\n";
        return "{\"summary\": \"Base62 implementation\", \"edits\": [{\"path\": "
                + "\"src/main/java/com/example/codec/core/Base62Codec.java\", \"action\": \"create\", \"content\": \""
                + code + "\"}], \"decisions\": []}";
    }

    static final String TESTS = """
            {"summary": "AC1 and AC2 tests",
             "edits": [{"path": "src/test/java/com/example/codec/CodecTest.java", "action": "create",
               "content": "package com.example.codec;\\n\\nimport com.example.codec.api.Codec;\\nimport com.example.codec.core.Base62Codec;\\nimport org.junit.jupiter.api.Test;\\n\\nimport static org.assertj.core.api.Assertions.assertThat;\\n\\nclass CodecTest {\\n    private final Codec codec = new Base62Codec();\\n\\n    @Test\\n    void ac1_zeroEncodesAsZero() {\\n        assertThat(codec.encode(0)).isEqualTo(\\"0\\");\\n    }\\n\\n    @Test\\n    void ac2_roundTrip() {\\n        for (long id : new long[]{1, 61, 62, 3843, 3844, 123456789L, Long.MAX_VALUE}) {\\n            assertThat(codec.decode(codec.encode(id))).isEqualTo(id);\\n        }\\n    }\\n}\\n"}],
             "decisions": []}
            """;

    static final String TESTS_OUTSIDE_PERMISSIONS = """
            {"summary": "also tweak production code", "edits": [
              {"path": "src/main/java/com/example/codec/Hack.java", "action": "create", "content": "class Hack {}"}],
             "decisions": []}
            """;

    static final String DOCS = """
            {"summary": "README", "edits": [{"path": "README.md", "action": "replace",
              "content": "# codec\\n\\nBase62 encoding of ids. `new Base62Codec().encode(62)` returns `10`.\\n"}],
             "decisions": []}
            """;
}
