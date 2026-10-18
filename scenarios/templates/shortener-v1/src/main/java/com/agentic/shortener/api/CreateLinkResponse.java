package com.agentic.shortener.api;

import java.time.Instant;

/** {@code managementToken} is shown only here; keep it to read the link's statistics. */
public record CreateLinkResponse(
        String code, String shortUrl, String targetUrl, Instant createdAt, Instant expiresAt, String managementToken) {
}
