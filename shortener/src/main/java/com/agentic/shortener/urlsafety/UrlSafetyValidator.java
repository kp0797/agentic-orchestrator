package com.agentic.shortener.urlsafety;

import com.agentic.shortener.common.ApiException;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * Accepts only http(s) URLs whose host resolves exclusively to public addresses.
 *
 * <p>The service never fetches targets itself; the check stops it from being used to point
 * people (or internal tools that follow links) at loopback, private, link-local (including the
 * cloud metadata address 169.254.169.254) or otherwise non-routable addresses. Every resolved
 * address must be public: one private record is enough to reject, because the client chooses
 * which record it uses.
 *
 * <p>Limitation: validation happens at creation. A host can be re-pointed to a private address
 * afterwards (DNS rebinding); re-validating on redirect is listed as follow-up work.
 */
public final class UrlSafetyValidator {
    private final HostResolver resolver;
    private final int maxLength;
    private final String ownHost;

    public UrlSafetyValidator(HostResolver resolver, int maxLength, String ownBaseUrl) {
        this.resolver = resolver;
        this.maxLength = maxLength;
        this.ownHost = hostOf(ownBaseUrl);
    }

    /** Returns the normalised URL, or throws a 400 {@link ApiException}. */
    public String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("url is required");
        }
        String url = raw.strip();
        if (url.length() > maxLength) {
            throw invalid("url is longer than " + maxLength + " characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw invalid("url is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw invalid("only http and https URLs are allowed");
        }
        if (uri.getRawUserInfo() != null) {
            // "https://trusted.example@evil.example" reads as the first host but goes to the second.
            throw invalid("URLs with user info are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw invalid("url has no host");
        }
        host = stripBrackets(host).toLowerCase(Locale.ROOT);
        if (host.equals(ownHost)) {
            throw unsafe("links to this service would create redirect loops");
        }
        List<InetAddress> addresses = resolver.resolve(host);
        if (addresses.isEmpty()) {
            throw invalid("host '" + host + "' does not resolve");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw unsafe("host '" + host + "' resolves to a non-public address");
            }
        }
        return url;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicV4(b);
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {
                return false;                                   // fc00::/7 unique local
            }
            if (first == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
                return false;                                   // 2001:db8::/32 documentation
            }
            if (isPrefix(b, new int[]{0, 0x64, 0xff, 0x9b}) && allZero(b, 4, 12)) {
                return isPublicV4(tail4(b));                    // 64:ff9b::/96 NAT64 embeds IPv4
            }
            if (allZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                return isPublicV4(tail4(b));                    // ::ffff:0:0/96 IPv4-mapped
            }
            if (allZero(b, 0, 12)) {
                return false;                                   // ::/96 (deprecated IPv4-compatible)
            }
            return true;
        }
        return false;
    }

    private static boolean isPublicV4(byte[] b) {
        int a = b[0] & 0xff;
        int c = b[1] & 0xff;
        int d = b[2] & 0xff;
        if (a == 0 || a == 10 || a == 127 || a >= 224) {
            return false;                                       // this-network, private, loopback, multicast, reserved
        }
        if (a == 100 && c >= 64 && c <= 127) {
            return false;                                       // 100.64.0.0/10 carrier-grade NAT
        }
        if (a == 169 && c == 254) {
            return false;                                       // link-local, cloud metadata
        }
        if (a == 172 && c >= 16 && c <= 31) {
            return false;
        }
        if (a == 192 && c == 168) {
            return false;
        }
        if (a == 192 && c == 0 && (d == 0 || d == 2)) {
            return false;                                       // 192.0.0.0/24 protocol assignments, 192.0.2.0/24 docs
        }
        if (a == 198 && (c == 18 || c == 19)) {
            return false;                                       // 198.18.0.0/15 benchmarking
        }
        if (a == 198 && c == 51 && d == 100) {
            return false;                                       // documentation
        }
        return !(a == 203 && c == 0 && d == 113);               // documentation
    }

    private static boolean isPrefix(byte[] b, int[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xff) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] tail4(byte[] b) {
        return new byte[]{b[12], b[13], b[14], b[15]};
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static String hostOf(String baseUrl) {
        try {
            String h = URI.create(baseUrl).getHost();
            return h == null ? "" : stripBrackets(h).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static ApiException invalid(String detail) {
        return ApiException.badRequest("invalid-url", detail);
    }

    private static ApiException unsafe(String detail) {
        return ApiException.badRequest("unsafe-url", detail);
    }
}
