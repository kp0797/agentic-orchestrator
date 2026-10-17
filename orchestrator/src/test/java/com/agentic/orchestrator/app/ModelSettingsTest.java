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
    }
}
