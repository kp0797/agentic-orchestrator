package com.agentic.shortener.api;

import com.agentic.shortener.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AC3: each top referrer shows its unique visitors next to its clicks. */
class ReferrerVisitorsApiTest extends ApiTestBase {

    @Test
    void ac3_topReferrersShowUniqueVisitors() throws Exception {
        JsonNode link = createLink("https://example.com/campaign", null);
        String code = link.get("code").asText();
        visit(code, "https://news.example/a", "192.0.2.10");
        visit(code, "https://news.example/b", "192.0.2.10");
        visit(code, "https://news.example/c", "192.0.2.11");
        visit(code, "https://blog.example/x", "192.0.2.10");
        clickRecorder.drainNow();

        JsonNode referrers = json.readTree(mvc.perform(get("/api/links/" + code + "/stats")
                        .header("X-Management-Token", link.get("managementToken").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("topReferrers");

        assertThat(referrers.get(0).get("host").asText()).isEqualTo("news.example");
        assertThat(referrers.get(0).get("clicks").asLong()).isEqualTo(3);
        assertThat(referrers.get(0).get("uniqueVisitors").asLong()).isEqualTo(2);
        assertThat(referrers.get(1).get("host").asText()).isEqualTo("blog.example");
        assertThat(referrers.get(1).get("uniqueVisitors").asLong()).isEqualTo(1);
    }

    private void visit(String code, String referer, String ip) throws Exception {
        mvc.perform(get("/" + code).header("Referer", referer).header("User-Agent", "Firefox").with(request -> {
            request.setRemoteAddr(ip);
            return request;
        })).andExpect(status().isFound());
    }
}
