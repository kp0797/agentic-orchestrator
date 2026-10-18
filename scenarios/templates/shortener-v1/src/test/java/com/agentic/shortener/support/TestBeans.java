package com.agentic.shortener.support;

import com.agentic.shortener.urlsafety.HostResolver;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestBeans {
    @Bean
    @Primary
    HostResolver fakeHostResolver() {
        return new FakeHostResolver();
    }
}
