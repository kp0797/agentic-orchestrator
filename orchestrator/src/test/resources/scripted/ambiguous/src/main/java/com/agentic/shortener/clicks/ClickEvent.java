package com.agentic.shortener.clicks;

import java.time.Instant;

/**
 * One redirect. {@code referrerHost} is null for direct visits. {@code visitorHash} identifies the
 * visitor within one UTC day only (see {@link VisitorHasher}); null when unknown.
 */
public record ClickEvent(long linkId, Instant clickedAt, String referrerHost, String visitorHash) {

    public ClickEvent(long linkId, Instant clickedAt, String referrerHost) {
        this(linkId, clickedAt, referrerHost, null);
    }
}
