package com.agentic.shortener.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

/** Applies {@link CreateRateLimiter} to POST /api/links. Answers 429 with Retry-After when limited. */
public final class RateLimitInterceptor implements HandlerInterceptor {
    private final CreateRateLimiter limiter;

    public RateLimitInterceptor(CreateRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        // getRemoteAddr honours X-Forwarded-For only when server.forward-headers-strategy is set,
        // so clients cannot pick their own rate-limit key by sending the header.
        CreateRateLimiter.Decision decision = limiter.tryAcquire(request.getRemoteAddr());
        response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        if (decision.allowed()) {
            return true;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"https://sho.rt/problems/rate-limited","title":"Too Many Requests","status":429,\
                "detail":"link creation is limited per client; retry after %d seconds"}"""
                .formatted(decision.retryAfterSeconds()));
        return false;
    }
}
