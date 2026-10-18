package com.agentic.shortener.clicks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReferrerHostsTest {

    @Test
    void keepsOnlyTheLowercasedHost() {
        assertThat(ReferrerHosts.hostOf("https://News.Example.com/story?id=1&email=a@b.c")).isEqualTo("news.example.com");
    }

    @Test
    void missingOrUnparseableReferrersCountAsDirect() {
        assertThat(ReferrerHosts.hostOf(null)).isNull();
        assertThat(ReferrerHosts.hostOf("")).isNull();
        assertThat(ReferrerHosts.hostOf("not a url")).isNull();
        assertThat(ReferrerHosts.hostOf("android-app://")).isNull();
    }
}
