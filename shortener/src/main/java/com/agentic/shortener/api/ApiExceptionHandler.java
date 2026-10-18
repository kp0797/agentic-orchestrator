package com.agentic.shortener.api;

import com.agentic.shortener.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.stream.Collectors;

/** All errors are RFC 9457 problem details with a stable {@code type}. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    static final String TYPE_BASE = "https://sho.rt/problems/";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) {
        return problem(e.status(), e.type(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        String type = e.getBindingResult().getFieldErrors().stream().anyMatch(f -> f.getField().equals("url"))
                ? "invalid-url" : "invalid-request";
        return problem(HttpStatus.BAD_REQUEST, type, detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "request body is not valid JSON for this endpoint");
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> internal(IllegalStateException e) {
        log.error("request failed", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "temporarily-unavailable", "please retry");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create(TYPE_BASE + type));
        return ResponseEntity.status(status).body(body);
    }
}
