package com.agentic.orchestrator.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Recorded LLM exchanges keyed by request hash. The same request may be recorded several times (a
 * failing attempt and its retry can send identical prompts), so each key holds an ordered list and
 * replay hands them out in recording order.
 */
public final class Cassette {
    /** @param stopReason the model's stop reason; "max_tokens" means the answer was cut off */
    public record Entry(String key, String model, String promptHash, String response, long inputTokens,
                        long outputTokens, String stopReason) {
        public Entry(String key, String model, String promptHash, String response, long inputTokens,
                     long outputTokens) {
            this(key, model, promptHash, response, inputTokens, outputTokens, null);
        }
    }

    private final Map<String, List<Entry>> entries = new LinkedHashMap<>();
    private final Map<String, Integer> cursor = new LinkedHashMap<>();

    public synchronized void add(Entry entry) {
        entries.computeIfAbsent(entry.key(), k -> new ArrayList<>()).add(entry);
    }

    public synchronized Optional<Entry> next(String key) {
        List<Entry> list = entries.get(key);
        if (list == null) {
            return Optional.empty();
        }
        int i = cursor.getOrDefault(key, 0);
        if (i >= list.size()) {
            return Optional.empty();
        }
        cursor.put(key, i + 1);
        return Optional.of(list.get(i));
    }

    public synchronized List<Entry> all() {
        return entries.values().stream().flatMap(Collection::stream).toList();
    }

    public synchronized int size() {
        return entries.values().stream().mapToInt(List::size).sum();
    }
}
