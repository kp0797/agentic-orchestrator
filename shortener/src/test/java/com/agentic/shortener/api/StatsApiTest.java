package com.agentic.shortener.api;

import com.agentic.shortener.clicks.ClickEvent;
import com.agentic.shortener.clicks.ClickRepository;
import com.agentic.shortener.link.LinkRepository;
import com.agentic.shortener.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StatsApiTest extends ApiTestBase {
    @Autowired
    LinkRepository links;
    @Autowired
    ClickRepository clicks;

    @Test
    void statsRequireTheManagementToken() throws Exception {
        JsonNode link = createLink("https://example.com", null);
        String path = "/api/links/" + link.get("code").asText() + "/stats";

        mvc.perform(get(path)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://sho.rt/problems/invalid-management-token"));
        mvc.perform(get(path).header("X-Management-Token", "wrong")).andExpect(status().isUnauthorized());
        // Another link's valid token does not work either.
        String otherToken = createLink("https://example.com", null).get("managementToken").asText();
        mvc.perform(get(path).header("X-Management-Token", otherToken)).andExpect(status().isUnauthorized());

        mvc.perform(get(path).header("X-Management-Token", link.get("managementToken").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void unknownLinkIs404() throws Exception {
        mvc.perform(get("/api/links/missing1/stats").header("X-Management-Token", "x")).andExpect(status().isNotFound());
    }

    @Test
    void clicksPerUtcDayIncludeDaysWithoutClicks() throws Exception {
        JsonNode created = createLink("https://example.com", null);
        long id = links.findByCode(created.get("code").asText()).orElseThrow().id();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant todayNoon = today.atTime(12, 0).toInstant(ZoneOffset.UTC);
        // Two clicks two days ago (one a minute before midnight UTC), three today.
        Instant twoDaysAgo = todayNoon.minus(2, ChronoUnit.DAYS);
        clicks.insertBatch(List.of(
                new ClickEvent(id, twoDaysAgo, null),
                new ClickEvent(id, today.minusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusSeconds(60), null),
                new ClickEvent(id, todayNoon, "a.example"),
                new ClickEvent(id, todayNoon, "a.example"),
                new ClickEvent(id, todayNoon, null)));

        mvc.perform(get("/api/links/" + created.get("code").asText() + "/stats?days=3")
                        .header("X-Management-Token", created.get("managementToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(5))
                .andExpect(jsonPath("$.clicksPerDay.length()").value(3))
                .andExpect(jsonPath("$.clicksPerDay[0].date").value(today.minusDays(2).toString()))
                .andExpect(jsonPath("$.clicksPerDay[0].clicks").value(2))
                .andExpect(jsonPath("$.clicksPerDay[1].clicks").value(0))
                .andExpect(jsonPath("$.clicksPerDay[2].date").value(today.toString()))
                .andExpect(jsonPath("$.clicksPerDay[2].clicks").value(3));
    }

    @Test
    void topReferrersAreTheTenBusiestWithDirectTrafficLabelled() throws Exception {
        JsonNode created = createLink("https://example.com", null);
        long id = links.findByCode(created.get("code").asText()).orElseThrow().id();
        Instant now = Instant.now();
        List<ClickEvent> events = new ArrayList<>();
        for (int site = 1; site <= 12; site++) {
            for (int n = 0; n < site; n++) {
                events.add(new ClickEvent(id, now, "site" + site + ".example"));
            }
        }
        for (int n = 0; n < 20; n++) {
            events.add(new ClickEvent(id, now, null));
        }
        clicks.insertBatch(events);

        String body = mvc.perform(get("/api/links/" + created.get("code").asText() + "/stats")
                        .header("X-Management-Token", created.get("managementToken").asText()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode referrers = json.readTree(body).get("topReferrers");

        assertThat(referrers).hasSize(10);
        assertThat(referrers.get(0).get("host").asText()).isEqualTo("(direct)");
        assertThat(referrers.get(0).get("clicks").asLong()).isEqualTo(20);
        assertThat(referrers.get(1).get("host").asText()).isEqualTo("site12.example");
        assertThat(referrers.get(9).get("host").asText()).isEqualTo("site4.example");
        assertThat(json.readTree(body).get("clicksPerDay")).hasSize(30);
    }

    @Test
    void theReportingWindowIsBoundedByRetention() throws Exception {
        JsonNode created = createLink("https://example.com", null);
        mvc.perform(get("/api/links/" + created.get("code").asText() + "/stats?days=91")
                        .header("X-Management-Token", created.get("managementToken").asText()))
                .andExpect(status().isBadRequest());
    }
}
