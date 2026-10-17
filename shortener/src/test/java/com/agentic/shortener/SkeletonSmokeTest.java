package com.agentic.shortener;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps the skeleton module's build honest until the greenfield run adds real tests. */
class SkeletonSmokeTest {

    @Test
    void applicationClassIsPresent() {
        assertThat(ShortenerApplication.class.getPackageName()).isEqualTo("com.agentic.shortener");
    }
}
