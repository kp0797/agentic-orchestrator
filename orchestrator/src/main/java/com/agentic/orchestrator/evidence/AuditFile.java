package com.agentic.orchestrator.evidence;

import com.agentic.orchestrator.events.AuditVerifier;
import com.agentic.orchestrator.events.Event;
import com.agentic.orchestrator.events.EventType;
import com.agentic.orchestrator.planning.PlanningJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Audit logs as JSON lines, and verification of exported logs without the orchestrator's database. */
public final class AuditFile {
    private AuditFile() {}

    public static String encode(List<Event> events) {
        StringBuilder sb = new StringBuilder();
        for (Event e : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", e.runId());
            m.put("seq", e.seq());
            m.put("timestamp", e.timestamp().toString());
            m.put("type", e.type().name());
            m.put("nodeId", e.nodeId());
            m.put("data", e.data());
            m.put("prevHash", e.prevHash());
            m.put("hash", e.hash());
            sb.append(PlanningJson.write(m).replace("\n", "")).append('\n');
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static List<Event> decode(String jsonl) {
        List<Event> out = new ArrayList<>();
        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> m = PlanningJson.parse(line, Map.class);
            Map<String, String> data = new LinkedHashMap<>();
            ((Map<String, Object>) m.get("data")).forEach((k, v) -> data.put(k, String.valueOf(v)));
            out.add(new Event(String.valueOf(m.get("runId")), ((Number) m.get("seq")).longValue(),
                    Instant.parse(String.valueOf(m.get("timestamp"))), EventType.valueOf(String.valueOf(m.get("type"))),
                    String.valueOf(m.get("nodeId")), data, String.valueOf(m.get("prevHash")),
                    String.valueOf(m.get("hash"))));
        }
        return out;
    }

    /** Verifies every audit/*.jsonl under an evidence directory. */
    public static Map<String, AuditVerifier.Result> verifyDirectory(Path evidenceDir) {
        Map<String, AuditVerifier.Result> results = new LinkedHashMap<>();
        Path audit = evidenceDir.resolve("audit");
        try (Stream<Path> files = Files.list(audit)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) {
                AuditVerifier.Result r;
                try {
                    r = AuditVerifier.verify(decode(Files.readString(f)));
                } catch (RuntimeException e) {
                    r = new AuditVerifier.Result(false, 0, -1, "unreadable: " + e.getMessage(), null);
                }
                results.put(f.getFileName().toString(), r);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("no audit logs in " + audit, e);
        }
        return results;
    }
}
