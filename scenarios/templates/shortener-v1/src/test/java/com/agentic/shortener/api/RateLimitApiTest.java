package com.agentic.shortener.api;

import com.agentic.shortener.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "shortener.rate-limit.creates-per-minute=20")
class RateLimitApiTest extends ApiTestBase {

    @Test
    void the21stCreateInAMinuteIs429WithRetryAfter() throws Exception {
        for (int i = 0; i < 20; i++) {
            create("203.0.113.7").andExpect(status().isCreated());
        }
        create("203.0.113.7")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("https://sho.rt/problems/rate-limited"));

        // Other clients are unaffected, and redirects are never limited.
        create("198.51.100.9").andExpect(status().isCreated());
        mvc.perform(get("/nope404").with(from("203.0.113.7"))).andExpect(status().isNotFound());
    }

    @Test
    void forwardedForHeadersCannotDodgeTheLimitByDefault() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/api/links").with(from("192.0.2.44")).header("X-Forwarded-For", "10.9.9." + i)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com\"}"));
        }
        mvc.perform(post("/api/links").with(from("192.0.2.44")).header("X-Forwarded-For", "10.9.9.99")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isTooManyRequests());
    }

    private org.springframework.test.web.servlet.ResultActions create(String ip) throws Exception {
        return mvc.perform(post("/api/links").with(from(ip))
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com\"}"));
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
