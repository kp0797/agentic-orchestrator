package com.agentic.orchestrator.events;

import com.agentic.orchestrator.artifacts.Hashing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Canonical, dependency-free encoding used for hashing and for storing event data as text. */
public final class EventCodec {
    private EventCodec() {}

    static String hash(String runId, long seq, Instant ts, EventType type, String nodeId,
                       Map<String, String> data, String prevHash) {
        String canonical = runId + '\n' + seq + '\n' + ts + '\n' + type + '\n' + nodeId + '\n'
                + encodeData(data) + '\n' + prevHash;
        return Hashing.sha256(canonical);
    }

    /** Sorted {@code key=value} pairs separated by {@code ;}, with \, ; = and newlines escaped. */
    public static String encodeData(Map<String, String> data) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(data).entrySet()) {
            if (!sb.isEmpty()) {
                sb.append(';');
            }
            sb.append(escape(e.getKey())).append('=').append(escape(e.getValue() == null ? "" : e.getValue()));
        }
        return sb.toString();
    }

    public static Map<String, String> decodeData(String encoded) {
        Map<String, String> out = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        StringBuilder key = new StringBuilder();
        StringBuilder value = new StringBuilder();
        StringBuilder current = key;
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '\\' && i + 1 < encoded.length()) {
                char next = encoded.charAt(++i);
                current.append(next == 'n' ? '\n' : next);
            } else if (c == '=' && current == key) {
                current = value;
            } else if (c == ';') {
                out.put(key.toString(), value.toString());
                key.setLength(0);
                value.setLength(0);
                current = key;
            } else {
                current.append(c);
            }
        }
        out.put(key.toString(), value.toString());
        return out;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case ';' -> sb.append("\\;");
                case '=' -> sb.append("\\=");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
