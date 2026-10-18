package com.agentic.shortener.api;

import com.agentic.shortener.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CreateLinkApiTest extends ApiTestBase {

    @Test
    void createsALinkWithAGeneratedCodeAndAManagementToken() throws Exception {
        JsonNode body = json.readTree(postLink("{\"url\":\"https://example.com/a/very/long/path?x=1\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("http://sho.rt/")))
                .andReturn().getResponse().getContentAsString());

        assertThat(body.get("code").asText()).matches("[0-9A-Za-z]{7}");
        assertThat(body.get("shortUrl").asText()).isEqualTo("http://sho.rt/" + body.get("code").asText());
        assertThat(body.get("targetUrl").asText()).isEqualTo("https://example.com/a/very/long/path?x=1");
        assertThat(body.get("managementToken").asText()).hasSize(43);
        assertThat(body.get("expiresAt").isNull()).isTrue();
    }

    @Test
    void sameUrlTwiceGivesTwoIndependentLinks() throws Exception {
        String a = createLink("https://example.com/same", null).get("code").asText();
        String b = createLink("https://example.com/same", null).get("code").asText();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void acceptsACustomAlias() throws Exception {
        String alias = uniqueAlias();
        postLink("{\"url\":\"https://example.com\",\"alias\":\"%s\"}".formatted(alias))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(alias))
                .andExpect(jsonPath("$.shortUrl", endsWith("/" + alias)));
    }

    @Test
    void aTakenAliasIsAConflict() throws Exception {
        String alias = uniqueAlias();
        createLink("https://example.com/1", alias);
        postLink("{\"url\":\"https://example.com/2\",\"alias\":\"%s\"}".formatted(alias))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://sho.rt/problems/alias-taken"));
    }

    @Test
    void aliasesAreCaseSensitive() throws Exception {
        String alias = "Case-" + uniqueAlias();
        createLink("https://example.com/1", alias);
        createLink("https://example.com/2", alias.toLowerCase());
    }

    @Test
    void invalidAndReservedAliasesAreRejected() throws Exception {
        postLink("{\"url\":\"https://example.com\",\"alias\":\"no\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sho.rt/problems/invalid-alias"));
        postLink("{\"url\":\"https://example.com\",\"alias\":\"has/slash\"}")
                .andExpect(status().isBadRequest());
        postLink("{\"url\":\"https://example.com\",\"alias\":\"Actuator\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sho.rt/problems/reserved-alias"));
    }

    @Test
    void unsafeUrlsAreRejectedAtCreation() throws Exception {
        postLink("{\"url\":\"http://169.254.169.254/latest/meta-data/\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sho.rt/problems/unsafe-url"));
        postLink("{\"url\":\"http://internal.test/\"}").andExpect(status().isBadRequest());
        postLink("{\"url\":\"javascript:alert(1)\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sho.rt/problems/invalid-url"));
    }

    @Test
    void missingUrlAndMalformedJsonAreBadRequests() throws Exception {
        postLink("{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("https://sho.rt/problems/invalid-url"));
        postLink("{\"url\": ").andExpect(status().isBadRequest());
        postLink("{\"url\":\"https://example.com\",\"expiresAt\":\"tomorrow\"}").andExpect(status().isBadRequest());
    }

    @Test
    void expiryMustBeInTheFutureAndWithinAYear() throws Exception {
        Instant past = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant tooFar = Instant.now().plus(366, ChronoUnit.DAYS);
        Instant ok = Instant.now().plus(30, ChronoUnit.DAYS);

        postLink("{\"url\":\"https://example.com\",\"expiresAt\":\"%s\"}".formatted(past))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sho.rt/problems/invalid-expiry"));
        postLink("{\"url\":\"https://example.com\",\"expiresAt\":\"%s\"}".formatted(tooFar))
                .andExpect(status().isBadRequest());
        postLink("{\"url\":\"https://example.com\",\"expiresAt\":\"%s\"}".formatted(ok))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }
}
