package com.agentic.shortener.common;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62CodeGeneratorTest {

    @Test
    void producesSevenCharacterBase62Codes() {
        Base62CodeGenerator generator = new Base62CodeGenerator(new SecureRandom(), 7);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String code = generator.next();
            assertThat(code).hasSize(7).matches("[0-9A-Za-z]{7}");
            seen.add(code);
        }
        assertThat(seen).hasSize(10_000);
    }

    @Test
    void usesTheWholeAlphabet() {
        Base62CodeGenerator generator = new Base62CodeGenerator(new SecureRandom(), 7);
        Set<Character> chars = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            generator.next().chars().forEach(c -> chars.add((char) c));
        }
        assertThat(chars).hasSize(62);
    }

    @Test
    void refusesUselesslyShortCodes() {
        assertThatThrownBy(() -> new Base62CodeGenerator(new SecureRandom(), 3)).isInstanceOf(IllegalArgumentException.class);
    }
}
