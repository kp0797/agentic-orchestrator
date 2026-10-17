package com.agentic.orchestrator.planning;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** JSON for specs and plans. Tolerates a model wrapping its JSON in prose or code fences. */
public final class PlanningJson {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private PlanningJson() {}

    /** The outermost JSON object in a model response. */
    public static String extractObject(String text) {
        if (text == null) {
            throw new IllegalArgumentException("empty response");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("response contains no JSON object");
        }
        return text.substring(start, end + 1);
    }

    public static <T> T parse(String text, Class<T> type) {
        try {
            return MAPPER.readValue(extractObject(text), type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("invalid " + type.getSimpleName() + " JSON: "
                    + e.getOriginalMessage(), e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
