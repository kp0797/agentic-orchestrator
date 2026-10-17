package com.agentic.orchestrator.agents;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptLibraryTest {
    private final PromptLibrary prompts = new PromptLibrary();

    @Test
    void everyShippedPromptHasAVersionAndBothParts() {
        for (String name : new String[]{"requirements", "planner", "edit-agent"}) {
            PromptLibrary.Template t = prompts.load(name);
            assertThat(t.version()).isEqualTo("v1");
            assertThat(t.system()).isNotBlank();
            assertThat(t.user()).isNotBlank();
        }
    }

    @Test
    void valuesAreInsertedOnceAndMayContainBraces() {
        String out = PromptLibrary.fill("A {{x}} B {{y}}", Map.of("x", "uses {{y}} literally", "y", "why"));
        assertThat(out).isEqualTo("A uses {{y}} literally B why");
    }

    @Test
    void missingVariablesFailLoudly() {
        assertThatThrownBy(() -> PromptLibrary.fill("{{missing}}", Map.of()))
                .hasMessageContaining("'missing' was not provided");
    }

    @Test
    void promptHashChangesWithContent() {
        var a = prompts.render("requirements", Map.of("requirement", "a", "feedback", ""));
        var b = prompts.render("requirements", Map.of("requirement", "b", "feedback", ""));
        assertThat(a.hash()).isNotEqualTo(b.hash());
        assertThat(a.template().id()).isEqualTo("requirements@v1");
    }
}
