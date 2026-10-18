package com.agentic.shortener.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Management tokens let a link's creator read its statistics without user accounts. The token is
 * returned once at creation; only its SHA-256 is stored, so a database leak does not leak tokens.
 * (A plain hash is enough: tokens are 256 random bits, so there is nothing to brute-force.)
 */
public final class ManagementTokens {
    private static final SecureRandom RANDOM = new SecureRandom();

    private ManagementTokens() {
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison, so response timing reveals nothing about the stored hash. */
    public static boolean matches(String presentedToken, String storedHash) {
        if (presentedToken == null || presentedToken.isBlank() || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(presentedToken).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
