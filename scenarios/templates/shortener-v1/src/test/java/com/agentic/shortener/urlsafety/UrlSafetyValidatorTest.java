package com.agentic.shortener.urlsafety;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.support.FakeHostResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlSafetyValidatorTest {
    private final UrlSafetyValidator validator = new UrlSafetyValidator(new FakeHostResolver(), 2048, "https://sho.rt");

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/some/path?q=1#frag",
            "http://example.com",
            "HTTPS://EXAMPLE.COM/x",
            "https://www.example.org:8443/",
            "http://8.8.8.8/",
            "http://[2606:4700:4700::1111]/"})
    void acceptsPublicHttpUrls(String url) {
        assertThat(validator.validate(url)).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/file", "javascript:alert(1)", "file:///etc/passwd", "data:text/html,hi",
            "example.com", "//example.com/x", "https://", "https://exa mple.com/", "  "})
    void rejectsNonHttpOrMalformedUrls(String url) {
        assertRejected(url, "invalid-url");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:8080/admin", "http://127.0.0.1/", "http://127.10.0.1/", "http://0.0.0.0/",
            "http://10.1.2.3/", "http://172.16.0.1/", "http://172.31.255.255/", "http://192.168.0.1/",
            "http://169.254.169.254/latest/meta-data/", "http://100.64.0.1/", "http://192.0.2.1/",
            "http://198.18.0.1/", "http://224.0.0.1/", "http://255.255.255.255/",
            "http://[::1]/", "http://[::]/", "http://[fe80::1]/", "http://[fc00::1]/", "http://[fd12:3456::1]/",
            "http://[::ffff:127.0.0.1]/", "http://[::ffff:10.0.0.1]/", "http://[64:ff9b::a00:1]/",
            "http://[2001:db8::1]/",
            "http://internal.test/", "http://metadata.test/", "http://mixed.test/"})
    void rejectsNonPublicDestinations(String url) {
        assertRejected(url, "unsafe-url");
    }

    @Test
    void rejectsUserInfoBecauseItDisguisesTheRealHost() {
        assertRejected("https://example.com@internal.test/", "invalid-url");
    }

    @Test
    void rejectsHostsThatDoNotResolve() {
        assertThatThrownBy(() -> validator.validate("https://no-such-host.test/"))
                .isInstanceOf(ApiException.class).hasMessageContaining("does not resolve");
    }

    @Test
    void rejectsLinksToItselfToPreventRedirectLoops() {
        assertRejected("https://sho.rt/abc1234", "unsafe-url");
    }

    @Test
    void rejectsOverlongUrls() {
        assertRejected("https://example.com/" + "a".repeat(2048), "invalid-url");
    }

    @Test
    void nat64AndMappedAddressesAreJudgedByTheEmbeddedIpv4Address() throws Exception {
        assertThat(UrlSafetyValidator.isPublic(InetAddress.getByName("64:ff9b::808:808"))).isTrue();
        assertThat(UrlSafetyValidator.isPublic(InetAddress.getByName("64:ff9b::7f00:1"))).isFalse();
    }

    private void assertRejected(String url, String type) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.type()).as(url).isEqualTo(type);
                    assertThat(e.status().value()).isEqualTo(400);
                });
    }
}
