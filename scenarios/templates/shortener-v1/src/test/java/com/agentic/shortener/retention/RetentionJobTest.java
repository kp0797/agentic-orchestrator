package com.agentic.shortener.retention;

import com.agentic.shortener.clicks.ClickEvent;
import com.agentic.shortener.clicks.ClickRepository;
import com.agentic.shortener.common.ManagementTokens;
import com.agentic.shortener.link.Link;
import com.agentic.shortener.link.LinkRepository;
import com.agentic.shortener.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionJobTest extends ApiTestBase {
    @Autowired
    RetentionJob job;
    @Autowired
    LinkRepository links;
    @Autowired
    ClickRepository clicks;

    @Test
    void deletesClicksOlderThan90DaysAndLinksExpiredMoreThan90DaysAgo() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Link live = insert(now.minus(200, ChronoUnit.DAYS), null);
        Link recentlyExpired = insert(now.minus(100, ChronoUnit.DAYS), now.minus(10, ChronoUnit.DAYS));
        Link longExpired = insert(now.minus(300, ChronoUnit.DAYS), now.minus(91, ChronoUnit.DAYS));
        clicks.insertBatch(List.of(
                new ClickEvent(live.id(), now.minus(91, ChronoUnit.DAYS), null),
                new ClickEvent(live.id(), now.minus(89, ChronoUnit.DAYS), null),
                new ClickEvent(longExpired.id(), now.minus(95, ChronoUnit.DAYS), null)));

        RetentionJob.Result result = job.purge();

        assertThat(result.clicksDeleted()).isGreaterThanOrEqualTo(2);
        assertThat(result.linksDeleted()).isGreaterThanOrEqualTo(1);
        assertThat(clicks.total(live.id())).isEqualTo(1);
        assertThat(links.findByCode(live.code())).isPresent();
        assertThat(links.findByCode(recentlyExpired.code())).isPresent();
        assertThat(links.findByCode(longExpired.code())).isEmpty();
    }

    private Link insert(Instant created, Instant expires) {
        return links.insert("r-" + UUID.randomUUID().toString().substring(0, 8), "https://example.com",
                created, expires, ManagementTokens.hash("t"));
    }
}
