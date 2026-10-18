package com.agentic.shortener.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LinkStats(
        String code,
        String targetUrl,
        Instant createdAt,
        Instant expiresAt,
        long totalClicks,
        List<Day> clicksPerDay,
        List<Referrer> topReferrers) {

    public record Day(LocalDate date, long clicks) {
    }

    public record Referrer(String host, long clicks) {
    }
}
