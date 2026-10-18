package com.agentic.shortener.common;

import org.springframework.http.HttpStatus;

/** An error with a stable machine-readable type, rendered as RFC 9457 problem details. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String type;

    public ApiException(HttpStatus status, String type, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
    }

    public HttpStatus status() { return status; }

    public String type() { return type; }

    public static ApiException badRequest(String type, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, type, detail);
    }

    public static ApiException notFound(String code) {
        return new ApiException(HttpStatus.NOT_FOUND, "link-not-found", "no link with code '" + code + "'");
    }

    public static ApiException expired(String code) {
        return new ApiException(HttpStatus.GONE, "link-expired", "link '" + code + "' has expired");
    }

    public static ApiException aliasTaken(String alias) {
        return new ApiException(HttpStatus.CONFLICT, "alias-taken", "alias '" + alias + "' is already in use");
    }

    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid-management-token",
                "a valid X-Management-Token header is required");
    }
}
