package com.agentic.shortener.cache;

import java.util.Optional;

/** For tests and single-node setups without Redis ({@code shortener.cache.type=none}). */
public final class NoOpLinkCache implements LinkCache {
    @Override
    public Optional<CachedLink> get(String code) {
        return Optional.empty();
    }

    @Override
    public void put(String code, CachedLink link) {
    }
}
