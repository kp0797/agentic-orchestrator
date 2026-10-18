package com.agentic.shortener.link;

import java.time.Instant;

public record Link(long id, String code, String targetUrl, Instant createdAt, Instant expiresAt, String tokenHash) {

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
