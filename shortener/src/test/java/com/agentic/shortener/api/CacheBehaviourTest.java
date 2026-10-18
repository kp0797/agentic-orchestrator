package com.agentic.shortener.api;

import com.agentic.shortener.cache.CachedLink;
import com.agentic.shortener.cache.LinkCache;
import com.agentic.shortener.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Redirects read through the cache, and keep working when the cache throws. */
@Import(CacheBehaviourTest.SwitchableCacheConfig.class)
class CacheBehaviourTest extends ApiTestBase {

    static final class SwitchableCache implements LinkCache {
        final Map<String, CachedLink> entries = new ConcurrentHashMap<>();
        final AtomicBoolean broken = new AtomicBoolean();
        final AtomicInteger hits = new AtomicInteger();

        @Override
        public Optional<CachedLink> get(String code) {
            if (broken.get()) {
                throw new IllegalStateException("redis is down");
            }
            Optional<CachedLink> found = Optional.ofNullable(entries.get(code));
            found.ifPresent(f -> hits.incrementAndGet());
            return found;
        }

        @Override
        public void put(String code, CachedLink link) {
            if (broken.get()) {
                throw new IllegalStateException("redis is down");
            }
            entries.put(code, link);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SwitchableCacheConfig {
        @Bean
        @Primary
        SwitchableCache switchableCache() {
            return new SwitchableCache();
        }
    }

    @Autowired
    SwitchableCache cache;

    @Test
    void secondRedirectIsServedFromTheCache() throws Exception {
        cache.broken.set(false);
        String code = createLink("https://example.com/cached", null).get("code").asText();
        int before = cache.hits.get();

        mvc.perform(get("/" + code)).andExpect(status().isFound());
        mvc.perform(get("/" + code)).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/cached"));

        assertThat(cache.entries).containsKey(code);
        assertThat(cache.hits.get() - before).isEqualTo(1);
    }

    @Test
    void redirectsKeepWorkingWhenTheCacheFails() throws Exception {
        String code = createLink("https://example.com/no-cache", null).get("code").asText();
        cache.broken.set(true);
        try {
            mvc.perform(get("/" + code)).andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://example.com/no-cache"));
        } finally {
            cache.broken.set(false);
        }
    }
}
