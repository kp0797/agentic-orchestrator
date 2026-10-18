package com.agentic.shortener.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManagementTokensTest {

    @Test
    void tokensAreLongRandomAndUrlSafe() {
        String a = ManagementTokens.newToken();
        String b = ManagementTokens.newToken();
        assertThat(a).hasSize(43).matches("[A-Za-z0-9_-]+").isNotEqualTo(b);
    }

    @Test
    void onlyTheHashIsNeededToVerify() {
        String token = ManagementTokens.newToken();
        String hash = ManagementTokens.hash(token);
        assertThat(hash).hasSize(64).doesNotContain(token);
        assertThat(ManagementTokens.matches(token, hash)).isTrue();
        assertThat(ManagementTokens.matches(token + "x", hash)).isFalse();
        assertThat(ManagementTokens.matches(null, hash)).isFalse();
        assertThat(ManagementTokens.matches("", hash)).isFalse();
    }
}
