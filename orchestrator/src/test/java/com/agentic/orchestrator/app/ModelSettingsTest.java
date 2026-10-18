package com.agentic.orchestrator.app;

import com.agentic.orchestrator.model.ModelSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Model settings are part of every recorded request key. If application.yml and the code defaults
 * drift apart, replay in the console misses while replay in tests passes. This keeps them identical.
 */
class ModelSettingsTest {

    @Test
    void applicationYamlMatchesCodeDefaults() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
        AgenticProperties props = Binder.get(env).bind("agentic", AgenticProperties.class).get();
        ModelSettings fromYaml = new RunService(props).modelSettings();
        ModelSettings defaults = ModelSettings.defaults();

        assertThat(fromYaml.agents()).isEqualTo(defaults.agents());
        assertThat(fromYaml.prices()).isEqualTo(defaults.prices());
        assertThat(props.llm().provider()).isEqualTo("anthropic");
        assertThat(props.llm().tokenParam()).isEqualTo("max_tokens");
    }

    /** Docker Compose passes unset per-agent variables as empty strings: they must keep the defaults. */
    @Test
    void blankPerAgentOverridesKeepTheDefaultAndOthersApply() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("compose",
                java.util.Map.of("agentic.models.coder.model", "", "agentic.models.docs.model", "small-model")));
        new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
        AgenticProperties props = Binder.get(env).bind("agentic", AgenticProperties.class).get();
        ModelSettings settings = new RunService(props).modelSettings();

        assertThat(settings.forAgent("coder")).isEqualTo(ModelSettings.defaults().forAgent("coder"));
        assertThat(settings.forAgent("docs").model()).isEqualTo("small-model");
    }

    /** AGENTIC_LLM_MODEL: one model for every agent (for example a single local model); caps are kept. */
    @Test
    void oneModelForEveryAgent() {
        ModelSettings all = ModelSettings.defaults().withEveryAgentUsing("local-model");
        assertThat(all.agents().values()).allSatisfy(m -> assertThat(m.model()).isEqualTo("local-model"));
        assertThat(all.forAgent("unknown-agent").model()).isEqualTo("local-model");
        assertThat(all.forAgent("coder").maxTokens()).isEqualTo(ModelSettings.defaults().forAgent("coder").maxTokens());
        assertThat(all.hasPrice("local-model")).isFalse();
        assertThat(all.cost("local-model", 1_000_000, 1_000_000)).isZero();
    }
}
