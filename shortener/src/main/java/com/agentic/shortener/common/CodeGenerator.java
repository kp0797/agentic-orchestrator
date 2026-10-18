package com.agentic.shortener.common;

/** Produces candidate short codes. Uniqueness is enforced by the database, not here. */
@FunctionalInterface
public interface CodeGenerator {
    String next();
}
