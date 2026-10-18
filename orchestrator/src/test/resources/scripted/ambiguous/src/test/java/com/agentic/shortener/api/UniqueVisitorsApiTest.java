package com.agentic.shortener.api;

import com.agentic.shortener.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AC1 and AC2: unique visitors per UTC day, counted without storing who the visitors are. */
class UniqueVisitorsApiTest extends ApiTestBase {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void ac1_statsShowUniqueVisitorsPerDayNextToClicks() throws Exception {
        JsonNode link = createLink("https://example.com/visitors", null);
        String code = link.get("code").asText();
        visit(code, "198.51.100.1", "Firefox");
        visit(code, "198.51.100.1", "Firefox");
        visit(code, "198.51.100.2", "Firefox");
        visit(code, "198.51.100.1", "Safari");    // same IP, other browser: another visitor
        clickRecorder.drainNow();

        JsonNode today = stats(link).get("clicksPerDay").get(29);

        assertThat(today.get("clicks").asLong()).isEqualTo(4);
        assertThat(today.get("uniqueVisitors").asLong()).isEqualTo(3);
    }

    @Test
    void ac2_neitherTheIpAddressNorTheUserAgentIsStored() throws Exception {
        JsonNode link = createLink("https://example.com/private", null);
        visit(link.get("code").asText(), "203.0.113.77", "Mozilla/5.0 (UniqueAgent)");
        clickRecorder.drainNow();

        List<String> columns = jdbc.queryForList("SELECT LOWER(column_name) FROM information_schema.columns "
                + "WHERE LOWER(table_name) = 'click_events'", String.class);
        assertThat(columns).noneMatch(c -> c.contains("ip") || c.contains("agent"));
        List<String> stored = jdbc.queryForList("SELECT CAST(visitor_hash AS VARCHAR(64)) FROM click_events c "
                + "JOIN links l ON l.id = c.link_id WHERE l.code = ?", String.class, link.get("code").asText());
        assertThat(stored).singleElement().satisfies(hash -> {
            assertThat(hash).matches("[0-9a-f]{64}");
            // Salted: not the plain hash of what was seen, so it cannot be looked up offline.
            assertThat(hash).isNotEqualTo(sha256("203.0.113.77\u0000Mozilla/5.0 (UniqueAgent)"));
        });
        String everything = String.join(" ", jdbc.queryForList("SELECT CONCAT(COALESCE(referrer_host, ''), ' ', "
                + "COALESCE(CAST(visitor_hash AS VARCHAR(64)), '')) FROM click_events", String.class));
        assertThat(everything).doesNotContain("203.0.113.77").doesNotContain("UniqueAgent");
    }

    private void visit(String code, String ip, String userAgent) throws Exception {
        mvc.perform(get("/" + code).header("User-Agent", userAgent).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        })).andExpect(status().isFound());
    }

    private JsonNode stats(JsonNode link) throws Exception {
        return json.readTree(mvc.perform(get("/api/links/" + link.get("code").asText() + "/stats")
                        .header("X-Management-Token", link.get("managementToken").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
