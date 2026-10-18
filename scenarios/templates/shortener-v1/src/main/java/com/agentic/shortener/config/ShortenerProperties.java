package com.agentic.shortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Everything tunable about the service, under {@code shortener.*}. */
@ConfigurationProperties("shortener")
public class ShortenerProperties {
    /** Public origin used to build short URLs, e.g. https://sho.rt. Its host can never be a target. */
    private String baseUrl = "http://localhost:8081";
    private int codeLength = 7;
    private int maxUrlLength = 2048;
    private int maxExpiryDays = 365;
    /** Aliases that would shadow routes or confuse users. Compared case-insensitively. */
    private List<String> reservedAliases = List.of(
            "api", "actuator", "admin", "assets", "docs", "error", "favicon", "health", "help",
            "login", "logout", "metrics", "prometheus", "robots", "static", "stats", "status",
            "swagger", "swagger-ui", "v3");
    private final Cache cache = new Cache();
    private final RateLimit rateLimit = new RateLimit();
    private final Clicks clicks = new Clicks();
    private final Retention retention = new Retention();

    public static class Cache {
        private String type = "redis";
        private Duration ttl = Duration.ofHours(1);

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
    }

    public static class RateLimit {
        private int createsPerMinute = 20;

        public int getCreatesPerMinute() { return createsPerMinute; }
        public void setCreatesPerMinute(int createsPerMinute) { this.createsPerMinute = createsPerMinute; }
    }

    public static class Clicks {
        private int queueCapacity = 10_000;
        private int batchSize = 500;

        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }

    public static class Retention {
        private int clickDays = 90;
        private int expiredLinkDays = 90;
        private String cron = "0 30 3 * * *";

        public int getClickDays() { return clickDays; }
        public void setClickDays(int clickDays) { this.clickDays = clickDays; }
        public int getExpiredLinkDays() { return expiredLinkDays; }
        public void setExpiredLinkDays(int expiredLinkDays) { this.expiredLinkDays = expiredLinkDays; }
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl; }
    public int getCodeLength() { return codeLength; }
    public void setCodeLength(int codeLength) { this.codeLength = codeLength; }
    public int getMaxUrlLength() { return maxUrlLength; }
    public void setMaxUrlLength(int maxUrlLength) { this.maxUrlLength = maxUrlLength; }
    public int getMaxExpiryDays() { return maxExpiryDays; }
    public void setMaxExpiryDays(int maxExpiryDays) { this.maxExpiryDays = maxExpiryDays; }
    public List<String> getReservedAliases() { return reservedAliases; }
    public void setReservedAliases(List<String> reservedAliases) { this.reservedAliases = List.copyOf(reservedAliases); }
    public Cache getCache() { return cache; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Clicks getClicks() { return clicks; }
    public Retention getRetention() { return retention; }
}
