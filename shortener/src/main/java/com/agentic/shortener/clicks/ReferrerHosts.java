package com.agentic.shortener.clicks;

import java.net.URI;
import java.util.Locale;

/** Keeps only the host of a Referer header: paths and queries can carry personal data. */
public final class ReferrerHosts {
    static final int MAX_LENGTH = 255;

    private ReferrerHosts() {
    }

    public static String hostOf(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(referer.strip()).getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.length() > MAX_LENGTH ? host.substring(0, MAX_LENGTH) : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
