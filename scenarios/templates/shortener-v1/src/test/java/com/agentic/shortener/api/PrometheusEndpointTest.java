package com.agentic.shortener.api;

import com.agentic.shortener.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The service's own metrics, including the click pipeline's drop counter, are scrapeable by Prometheus.
 * Spring Boot tests switch metrics exporters off unless asked: @AutoConfigureObservability turns the
 * Prometheus registry back on, as in production.
 */
@AutoConfigureObservability
class PrometheusEndpointTest extends ApiTestBase {

    @Test
    void exposesServiceAndHttpMetricsForPrometheus() throws Exception {
        String code = createLink("https://example.com/metrics", null).get("code").asText();
        mvc.perform(get("/" + code)).andExpect(status().isFound());
        clickRecorder.drainNow();

        String body = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("shortener_clicks_recorded_total", "shortener_clicks_dropped_total",
                "shortener_clicks_queued", "http_server_requests_seconds_count");
    }
}
