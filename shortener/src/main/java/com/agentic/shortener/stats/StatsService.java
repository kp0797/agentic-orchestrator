package com.agentic.shortener.stats;

import com.agentic.shortener.clicks.ClickRepository;
import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.ManagementTokens;
import com.agentic.shortener.link.Link;
import com.agentic.shortener.link.LinkRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StatsService {
    public static final String DIRECT = "(direct)";
    static final int TOP_REFERRERS = 10;
    static final int MAX_DAYS = 90;

    private final LinkRepository links;
    private final ClickRepository clicks;
    private final Clock clock;

    public StatsService(LinkRepository links, ClickRepository clicks, Clock clock) {
        this.links = links;
        this.clicks = clicks;
        this.clock = clock;
    }

    /**
     * Statistics for one link. Requires its management token; a wrong or missing token gets the
     * same 401 as any other, so the response does not help guess tokens.
     *
     * @param days how many UTC days to report, ending today; days without clicks are reported as 0
     */
    public LinkStats stats(String code, String managementToken, int days) {
        Link link = links.findByCode(code).orElseThrow(() -> ApiException.notFound(code));
        if (!ManagementTokens.matches(managementToken, link.tokenHash())) {
            throw ApiException.unauthorized();
        }
        if (days < 1 || days > MAX_DAYS) {
            throw ApiException.badRequest("invalid-days", "days must be between 1 and " + MAX_DAYS);
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate from = today.minusDays(days - 1L);
        Map<LocalDate, Long> counted = clicks.perDay(link.id(), from).stream()
                .collect(Collectors.toMap(ClickRepository.DayCount::day, ClickRepository.DayCount::clicks));
        List<LinkStats.Day> perDay = new ArrayList<>(days);
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            perDay.add(new LinkStats.Day(d, counted.getOrDefault(d, 0L)));
        }
        List<LinkStats.Referrer> referrers = clicks.topReferrers(link.id(), TOP_REFERRERS).stream()
                .map(r -> new LinkStats.Referrer(r.host() == null ? DIRECT : r.host(), r.clicks()))
                .toList();
        return new LinkStats(link.code(), link.targetUrl(), link.createdAt(), link.expiresAt(),
                clicks.total(link.id()), perDay, referrers);
    }
}
