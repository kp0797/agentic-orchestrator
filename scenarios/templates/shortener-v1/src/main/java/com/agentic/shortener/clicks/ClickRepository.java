package com.agentic.shortener.clicks;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class ClickRepository {
    public record DayCount(LocalDate day, long clicks) {
    }

    public record ReferrerCount(String host, long clicks) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public ClickRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public void insertBatch(List<ClickEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        template.batchUpdate(
                "INSERT INTO click_events (link_id, clicked_at, clicked_day, referrer_host) VALUES (?, ?, ?, ?)",
                events, events.size(), (ps, e) -> {
                    ps.setLong(1, e.linkId());
                    ps.setObject(2, e.clickedAt().atOffset(ZoneOffset.UTC));
                    ps.setObject(3, utcDay(e.clickedAt()));
                    ps.setString(4, e.referrerHost());
                });
    }

    public long total(long linkId) {
        return jdbc.sql("SELECT COUNT(*) FROM click_events WHERE link_id = :id")
                .param("id", linkId).query(Long.class).single();
    }

    /** Days with at least one click, from {@code fromDay} inclusive. */
    public List<DayCount> perDay(long linkId, LocalDate fromDay) {
        return jdbc.sql("""
                        SELECT clicked_day, COUNT(*) AS clicks FROM click_events
                        WHERE link_id = :id AND clicked_day >= :from
                        GROUP BY clicked_day""")
                .param("id", linkId)
                .param("from", fromDay)
                .query((rs, n) -> new DayCount(rs.getObject("clicked_day", LocalDate.class), rs.getLong("clicks")))
                .list();
    }

    /** Busiest referrer hosts first; a null host means direct traffic. */
    public List<ReferrerCount> topReferrers(long linkId, int limit) {
        return jdbc.sql("""
                        SELECT referrer_host, COUNT(*) AS clicks FROM click_events
                        WHERE link_id = :id
                        GROUP BY referrer_host
                        ORDER BY clicks DESC, referrer_host ASC
                        LIMIT :limit""")
                .param("id", linkId)
                .param("limit", limit)
                .query((rs, n) -> new ReferrerCount(rs.getString("referrer_host"), rs.getLong("clicks")))
                .list();
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM click_events WHERE clicked_at < :cutoff")
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .update();
    }

    static LocalDate utcDay(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).toLocalDate();
    }
}
