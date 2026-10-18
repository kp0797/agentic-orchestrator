package com.agentic.shortener.link;

import com.agentic.shortener.common.ApiException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliasPolicyTest {
    private final AliasPolicy policy = new AliasPolicy(List.of("api", "actuator", "Stats"));

    @ParameterizedTest
    @ValueSource(strings = {"abc", "my-link", "My_Link_2026", "abcdefghijklmnopqrstuvwxyz012345"})
    void acceptsValidAliases(String alias) {
        assertThatCode(() -> policy.check(alias)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "has space", "slash/es", "dot.ted", "ünïcode", "%41bc", ""})
    void rejectsBadFormat(String alias) {
        assertThatThrownBy(() -> policy.check(alias)).isInstanceOf(ApiException.class).hasMessageContaining("3 to 32");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "API", "Actuator", "stats"})
    void rejectsReservedWordsCaseInsensitively(String alias) {
        assertThatThrownBy(() -> policy.check(alias)).isInstanceOf(ApiException.class).hasMessageContaining("reserved");
    }

    @org.junit.jupiter.api.Test
    void rejectsOverlongAlias() {
        assertThatThrownBy(() -> policy.check("x".repeat(33))).isInstanceOf(ApiException.class);
    }
}
