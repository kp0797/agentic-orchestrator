package com.agentic.orchestrator.model;

/** replay: recorded answers only, no key, no network. live: real calls. record: real calls, saved. */
public enum GatewayMode {
    REPLAY, LIVE, RECORD
}
