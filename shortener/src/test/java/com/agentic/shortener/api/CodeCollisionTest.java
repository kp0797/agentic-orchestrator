package com.agentic.shortener.api;

import com.agentic.shortener.common.CodeGenerator;
import com.agentic.shortener.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.Iterator;
import java.util.List;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A generated code that is already taken (or reserved) is retried, not reported to the caller. */
@Import(CodeCollisionTest.ScriptedCodes.class)
class CodeCollisionTest extends ApiTestBase {

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedCodes {
        @Bean
        @Primary
        CodeGenerator scriptedCodeGenerator() {
            Iterator<String> codes = List.of("Dup0001", "Dup0001", "metrics", "Dup0002",
                    "Dup0001", "Dup0002", "Dup0001", "Dup0002", "Dup0001").iterator();
            return codes::next;
        }
    }

    @Test
    void collisionsAndReservedWordsAreRetriedThenGiveUpAfterFiveAttempts() throws Exception {
        postLink("{\"url\":\"https://example.com\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("Dup0001"));
        // Dup0001 taken, "metrics" reserved, Dup0002 free.
        postLink("{\"url\":\"https://example.com\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("Dup0002"));
        // Five collisions in a row: a retryable 503, not a 500.
        postLink("{\"url\":\"https://example.com\"}").andExpect(status().isServiceUnavailable());
    }
}
