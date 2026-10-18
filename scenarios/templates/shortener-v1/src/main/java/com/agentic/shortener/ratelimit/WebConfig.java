package com.agentic.shortener.ratelimit;

import com.agentic.shortener.config.ShortenerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {
    private final CreateRateLimiter limiter;

    WebConfig(ShortenerProperties props) {
        this.limiter = new CreateRateLimiter(props.getRateLimit().getCreatesPerMinute(), 100_000);
    }

    @Bean
    CreateRateLimiter createRateLimiter() {
        return limiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(limiter)).addPathPatterns("/api/links");
    }
}
