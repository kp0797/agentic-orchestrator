package com.agentic.shortener.clicks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Turns IP address and user agent into a visitor id that is only stable within one UTC day:
 * SHA-256 over a random salt for that day, the IP and the user agent. The salt lives in memory and is
 * replaced when the day changes, so stored hashes cannot be linked across days or reversed to an IP
 * once the day is over. Neither the IP nor the user agent is ever stored.
 *
 * <p>Trade-off: each node has its own salt, so with several nodes one visitor can count once per node
 * that served them. A shared daily salt (in Redis, expiring after a day) would fix that.
 */
public final class VisitorHasher {
    private final Supplier<byte[]> newSalt;
    private LocalDate saltDay;
    private byte[] salt;

    public VisitorHasher() {
        this(() -> {
            byte[] b = new byte[32];
            new SecureRandom().nextBytes(b);
            return b;
        });
    }

    VisitorHasher(Supplier<byte[]> newSalt) {
        this.newSalt = newSalt;
    }

    public synchronized String hash(String ip, String userAgent, Instant at) {
        LocalDate day = LocalDate.ofInstant(at, ZoneOffset.UTC);
        if (!day.equals(saltDay)) {
            salt = newSalt.get();
            saltDay = day;
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(salt);
            sha.update((nz(ip) + "\u0000" + nz(userAgent)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
