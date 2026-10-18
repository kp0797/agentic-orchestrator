package com.agentic.shortener.link;

/** A new link plus its management token, which exists only in this response. */
public record CreatedLink(Link link, String managementToken) {
}
