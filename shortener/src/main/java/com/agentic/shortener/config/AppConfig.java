package com.agentic.shortener.config;

import com.agentic.shortener.cache.LinkCache;
import com.agentic.shortener.cache.NoOpLinkCache;
import com.agentic.shortener.cache.RedisLinkCache;
import com.agentic.shortener.common.Base62CodeGenerator;
import com.agentic.shortener.common.CodeGenerator;
import com.agentic.shortener.urlsafety.DnsHostResolver;
import com.agentic.shortener.urlsafety.HostResolver;
import com.agentic.shortener.urlsafety.UrlSafetyValidator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    HostResolver hostResolver() {
        return new DnsHostResolver(Duration.ofSeconds(2));
    }

    @Bean
    UrlSafetyValidator urlSafetyValidator(HostResolver resolver, ShortenerProperties props) {
        return new UrlSafetyValidator(resolver, props.getMaxUrlLength(), props.getBaseUrl());
    }

    @Bean
    @ConditionalOnMissingBean
    CodeGenerator codeGenerator(ShortenerProperties props) {
        return new Base62CodeGenerator(new SecureRandom(), props.getCodeLength());
    }

    @Bean
    @ConditionalOnMissingBean(LinkCache.class)
    @ConditionalOnProperty(name = "shortener.cache.type", havingValue = "redis", matchIfMissing = true)
    LinkCache redisLinkCache(StringRedisTemplate redis, ShortenerProperties props, MeterRegistry meters, Clock clock) {
        return new RedisLinkCache(redis, props.getCache().getTtl(), meters, clock);
    }

    @Bean
    @ConditionalOnMissingBean(LinkCache.class)
    @ConditionalOnProperty(name = "shortener.cache.type", havingValue = "none")
    LinkCache noOpLinkCache() {
        return new NoOpLinkCache();
    }
}
