package com.agentic.shortener.api;

import com.agentic.shortener.config.ShortenerProperties;
import com.agentic.shortener.link.CreateLinkCommand;
import com.agentic.shortener.link.CreatedLink;
import com.agentic.shortener.link.LinkService;
import com.agentic.shortener.stats.LinkStats;
import com.agentic.shortener.stats.StatsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/links")
public class LinkController {
    public static final String TOKEN_HEADER = "X-Management-Token";

    private final LinkService links;
    private final StatsService stats;
    private final String baseUrl;

    public LinkController(LinkService links, StatsService stats, ShortenerProperties props) {
        this.links = links;
        this.stats = stats;
        this.baseUrl = props.getBaseUrl();
    }

    @PostMapping
    public ResponseEntity<CreateLinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
        CreatedLink created = links.create(new CreateLinkCommand(request.url(), request.alias(), request.expiresAt()));
        String shortUrl = baseUrl + "/" + created.link().code();
        return ResponseEntity.created(URI.create(shortUrl)).body(new CreateLinkResponse(
                created.link().code(), shortUrl, created.link().targetUrl(), created.link().createdAt(),
                created.link().expiresAt(), created.managementToken()));
    }

    @GetMapping("/{code}/stats")
    public LinkStats stats(@PathVariable String code,
                           @RequestHeader(name = TOKEN_HEADER, required = false) String token,
                           @RequestParam(defaultValue = "30") int days) {
        return stats.stats(code, token, days);
    }
}
