package com.agentic.shortener.clicks;

import java.time.Instant;

/** One redirect. {@code referrerHost} is null for direct visits. */
public record ClickEvent(long linkId, Instant clickedAt, String referrerHost) {
}
