package com.agentic.shortener.cache;

import java.util.Optional;

/**
 * Read-through cache for redirects. Implementations must never throw: a cache outage degrades to
 * database reads, it does not fail redirects. (Callers still guard, as defence in depth.)
 */
public interface LinkCache {
    Optional<CachedLink> get(String code);

    void put(String code, CachedLink link);
}
