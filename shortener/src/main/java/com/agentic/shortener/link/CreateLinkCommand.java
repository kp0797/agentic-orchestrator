package com.agentic.shortener.link;

import java.time.Instant;

public record CreateLinkCommand(String url, String alias, Instant expiresAt) {
}
