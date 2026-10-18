package com.agentic.shortener.api;

import com.agentic.shortener.clicks.ClickRepository;
import com.agentic.shortener.common.ManagementTokens;
import com.agentic.shortener.link.Link;
import com.agentic.shortener.link.LinkRepository;
import com.agentic.shortener.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RedirectApiTest extends ApiTestBase {
    @Autowired
    LinkRepository links;
    @Autowired
    ClickRepository clicks;

    @Test
    void redirectsWithA302ThatIsNeverCached() throws Exception {
        JsonNode link = createLink("https://example.com/target?q=1", null);
        mvc.perform(get("/" + link.get("code").asText()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/target?q=1"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void unknownCodesAre404() throws Exception {
        mvc.perform(get("/nope404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://sho.rt/problems/link-not-found"));
    }

    @Test
    void expiredLinksAre410() throws Exception {
        String code = uniqueAlias();
        Instant created = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        links.insert(code, "https://example.com", created, created.plus(1, ChronoUnit.DAYS), ManagementTokens.hash("t"));

        mvc.perform(get("/" + code))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("https://sho.rt/problems/link-expired"));
    }

    @Test
    void routesOutsideTheCodeFormatAreNotRedirects() throws Exception {
        mvc.perform(get("/ab")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void eachRedirectRecordsAClickWithOnlyTheReferrerHost() throws Exception {
        JsonNode created = createLink("https://example.com", null);
        String code = created.get("code").asText();
        mvc.perform(get("/" + code).header("Referer", "https://News.example.net/story?user=alice")).andExpect(status().isFound());
        mvc.perform(get("/" + code)).andExpect(status().isFound());

        clickRecorder.drainNow();

        Link link = links.findByCode(code).orElseThrow();
        assertThat(clicks.total(link.id())).isEqualTo(2);
        assertThat(clicks.topReferrers(link.id(), 10))
                .extracting(ClickRepository.ReferrerCount::host)
                .containsExactlyInAnyOrder("news.example.net", null);
    }
}
