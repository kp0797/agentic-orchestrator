package com.agentic.shortener.support;

import com.agentic.shortener.clicks.ClickRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full application on H2 (PostgreSQL mode) with fake DNS and no Redis. No Docker, no network. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestBeans.class)
public abstract class ApiTestBase {
    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected ClickRecorder clickRecorder;

    protected ResultActions postLink(String body) throws Exception {
        return mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Creates a link and returns the response body. */
    protected JsonNode createLink(String url, String alias) throws Exception {
        String body = alias == null
                ? "{\"url\":\"%s\"}".formatted(url)
                : "{\"url\":\"%s\",\"alias\":\"%s\"}".formatted(url, alias);
        String response = postLink(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    protected static String uniqueAlias() {
        return "a-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
