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

    /** {@code uniqueVisitors}: distinct visitors that day (clicks from before visitor counting have none). */
    public record Day(LocalDate date, long clicks, long uniqueVisitors) {
    }

    public record Referrer(String host, long clicks, long uniqueVisitors) {
    }
}
