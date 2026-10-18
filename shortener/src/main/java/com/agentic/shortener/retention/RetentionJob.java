package com.agentic.shortener.retention;

import com.agentic.shortener.clicks.ClickRepository;
import com.agentic.shortener.config.ShortenerProperties;
import com.agentic.shortener.link.LinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Daily purge: click events older than 90 days, and links that expired more than 90 days ago
 * (their codes become free again only after that grace period, so old links answer 410, not
 * someone else's target). On several nodes the job runs on each; the deletes are idempotent.
 */
@Component
public class RetentionJob {
    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    public record Result(int clicksDeleted, int linksDeleted) {
    }

    private final ClickRepository clicks;
    private final LinkRepository links;
    private final Duration clickRetention;
    private final Duration expiredLinkRetention;
    private final Clock clock;

    public RetentionJob(ClickRepository clicks, LinkRepository links, ShortenerProperties props, Clock clock) {
        this.clicks = clicks;
        this.links = links;
        this.clickRetention = Duration.ofDays(props.getRetention().getClickDays());
        this.expiredLinkRetention = Duration.ofDays(props.getRetention().getExpiredLinkDays());
        this.clock = clock;
    }

    @Scheduled(cron = "${shortener.retention.cron}", zone = "UTC")
    public Result purge() {
        Instant now = clock.instant();
        int clicksDeleted = clicks.deleteOlderThan(now.minus(clickRetention));
        int linksDeleted = links.deleteExpiredBefore(now.minus(expiredLinkRetention));
        log.info("retention: deleted {} click events and {} expired links", clicksDeleted, linksDeleted);
        return new Result(clicksDeleted, linksDeleted);
    }
}
