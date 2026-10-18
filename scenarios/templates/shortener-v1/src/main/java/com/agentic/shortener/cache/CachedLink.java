package com.agentic.shortener.cache;

import java.time.Instant;

/** What a redirect needs, and nothing else (no token hash in the cache). */
public record CachedLink(long linkId, String targetUrl, Instant expiresAt) {
}
