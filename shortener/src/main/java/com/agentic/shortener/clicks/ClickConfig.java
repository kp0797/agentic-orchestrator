package com.agentic.shortener.clicks;

import com.agentic.shortener.config.ShortenerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ClickConfig {

    @Bean
    ClickRecorder clickRecorder(ClickRepository repository, ShortenerProperties props, MeterRegistry meters) {
        return new ClickRecorder(repository, props.getClicks().getQueueCapacity(), props.getClicks().getBatchSize(), meters);
    }
}
