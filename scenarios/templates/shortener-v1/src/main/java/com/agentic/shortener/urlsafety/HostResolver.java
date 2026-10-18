package com.agentic.shortener.urlsafety;

import java.net.InetAddress;
import java.util.List;

/** Resolves a host name. A port so tests (and the brownfield scenario) never touch real DNS. */
@FunctionalInterface
public interface HostResolver {
    /** All addresses for the host; empty when it does not resolve. Never throws for lookup failures. */
    List<InetAddress> resolve(String host);
}
