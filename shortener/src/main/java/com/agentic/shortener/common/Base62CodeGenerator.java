package com.agentic.shortener.common;

import java.util.random.RandomGenerator;

/**
 * Random base62 codes. Seven characters give 62^7 (about 3.5 trillion) codes, so at a billion links
 * the chance that one new code collides is below 0.03%; the service retries on a collision.
 * Random rather than sequential so codes cannot be enumerated.
 */
public final class Base62CodeGenerator implements CodeGenerator {
    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final RandomGenerator random;
    private final int length;

    public Base62CodeGenerator(RandomGenerator random, int length) {
        if (length < 4) {
            throw new IllegalArgumentException("code length must be at least 4");
        }
        this.random = random;
        this.length = length;
    }

    @Override
    public String next() {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(out);
    }
}
