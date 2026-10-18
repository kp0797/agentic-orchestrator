package com.agentic.shortener.support;

import com.agentic.shortener.urlsafety.HostResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Deterministic DNS for tests: a few named hosts, and IP literals parsed without any lookup. */
public final class FakeHostResolver implements HostResolver {
    private static final Map<String, List<String>> HOSTS = Map.of(
            "example.com", List.of("93.184.215.14"),
            "www.example.org", List.of("93.184.215.15", "2606:2800:21f:cb07:6820:80da:af6b:8b2c"),
            "internal.test", List.of("10.0.0.5"),
            "metadata.test", List.of("169.254.169.254"),
            "mixed.test", List.of("8.8.8.8", "192.168.1.10"),
            "localhost", List.of("127.0.0.1", "::1"));

    @Override
    public List<InetAddress> resolve(String host) {
        List<String> literals = isIpLiteral(host) ? List.of(host) : HOSTS.getOrDefault(host, List.of());
        return literals.stream().map(FakeHostResolver::parse).toList();
    }

    private static boolean isIpLiteral(String host) {
        return host.contains(":") || host.chars().allMatch(c -> c == '.' || Character.isDigit(c));
    }

    private static InetAddress parse(String literal) {
        try {
            return InetAddress.getByName(literal);   // literals are parsed, never looked up
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(Arrays.toString(new String[]{literal}), e);
        }
    }
}
