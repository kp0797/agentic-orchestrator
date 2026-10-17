package com.agentic.orchestrator.events;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventCodecProperties {

    @Property
    void encodeDecodeRoundTripsAnyData(@ForAll Map<String, String> data) {
        Map<String, String> clean = new java.util.TreeMap<>();
        data.forEach((k, v) -> clean.put(k, v == null ? "" : v));
        if (clean.isEmpty()) {
            assertThat(EventCodec.decodeData(EventCodec.encodeData(clean))).isEmpty();
            return;
        }
        assertThat(EventCodec.decodeData(EventCodec.encodeData(clean))).isEqualTo(clean);
    }
}
