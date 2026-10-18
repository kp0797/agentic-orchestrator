package com.agentic.shortener.redirect;

import com.agentic.shortener.cache.CachedLink;
import com.agentic.shortener.cache.LinkCache;
import com.agentic.shortener.clicks.ClickEvent;
import com.agentic.shortener.clicks.ClickRecorder;
import com.agentic.shortener.clicks.ReferrerHosts;
import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.link.LinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/** Hot path: cache, then database; expiry check; click handed off without waiting. */
@Service
public class RedirectService {
    private static final Logger log = LoggerFactory.getLogger(RedirectService.class);

    private final LinkCache cache;
    private final LinkRepository links;
    private final ClickRecorder clicks;
    private final Clock clock;

    public RedirectService(LinkCache cache, LinkRepository links, ClickRecorder clicks, Clock clock) {
        this.cache = cache;
        this.links = links;
        this.clicks = clicks;
        this.clock = clock;
    }

    /** Returns the target URL, or throws 404 (unknown) / 410 (expired). */
    public String resolve(String code, String referer) {
        CachedLink link = lookup(code);
        Instant now = clock.instant();
        if (link.expiresAt() != null && !now.isBefore(link.expiresAt())) {
            throw ApiException.expired(code);
        }
        clicks.record(new ClickEvent(link.linkId(), now, ReferrerHosts.hostOf(referer)));
        return link.targetUrl();
    }

    private CachedLink lookup(String code) {
        Optional<CachedLink> cached;
        try {
            cached = cache.get(code);
        } catch (RuntimeException e) {
            log.warn("link cache failed, reading from the database: {}", e.toString());
            cached = Optional.empty();
        }
        if (cached.isPresent()) {
            return cached.get();
        }
        CachedLink link = links.findByCode(code)
                .map(l -> new CachedLink(l.id(), l.targetUrl(), l.expiresAt()))
                .orElseThrow(() -> ApiException.notFound(code));
        try {
            cache.put(code, link);
        } catch (RuntimeException e) {
            log.warn("link cache write failed: {}", e.toString());
        }
        return link;
    }
}
