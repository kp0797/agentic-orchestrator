package com.agentic.shortener.api;

import com.agentic.shortener.redirect.RedirectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class RedirectController {
    private final RedirectService redirects;

    public RedirectController(RedirectService redirects) {
        this.redirects = redirects;
    }

    /**
     * 302, not 301: browsers cache 301s forever, which would hide clicks from the statistics and
     * keep redirecting after the link expires.
     */
    @GetMapping("/{code:[A-Za-z0-9_-]{3,32}}")
    public ResponseEntity<Void> redirect(@PathVariable String code,
                                         @RequestHeader(name = HttpHeaders.REFERER, required = false) String referer,
                                         @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent,
                                         HttpServletRequest request) {
        String target = redirects.resolve(code, referer, request.getRemoteAddr(), userAgent);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
