package com.agentic.shortener.link;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.CodeGenerator;
import com.agentic.shortener.common.ManagementTokens;
import com.agentic.shortener.config.ShortenerProperties;
import com.agentic.shortener.urlsafety.UrlSafetyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class LinkService {
    static final int MAX_CODE_ATTEMPTS = 5;
    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    private final LinkRepository links;
    private final UrlSafetyValidator urlValidator;
    private final CodeGenerator codes;
    private final AliasPolicy aliasPolicy;
    private final Duration maxExpiry;
    private final Clock clock;

    public LinkService(LinkRepository links, UrlSafetyValidator urlValidator, CodeGenerator codes,
                       ShortenerProperties props, Clock clock) {
        this.links = links;
        this.urlValidator = urlValidator;
        this.codes = codes;
        this.aliasPolicy = new AliasPolicy(props.getReservedAliases());
        this.maxExpiry = Duration.ofDays(props.getMaxExpiryDays());
        this.clock = clock;
    }

    public CreatedLink create(CreateLinkCommand command) {
        String url = urlValidator.validate(command.url());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = checkExpiry(command.expiresAt(), now);
        String token = ManagementTokens.newToken();
        String tokenHash = ManagementTokens.hash(token);

        if (command.alias() != null && !command.alias().isBlank()) {
            String alias = command.alias().strip();
            aliasPolicy.check(alias);
            try {
                // Insert and let the unique constraint decide. Two concurrent requests for the same
                // alias: exactly one insert succeeds, the other gets 409.
                return new CreatedLink(links.insert(alias, url, now, expiresAt, tokenHash), token);
            } catch (DuplicateKeyException e) {
                throw ApiException.aliasTaken(alias);
            }
        }

        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            String code = codes.next();
            if (aliasPolicy.isReserved(code)) {
                continue;
            }
            try {
                return new CreatedLink(links.insert(code, url, now, expiresAt, tokenHash), token);
            } catch (DuplicateKeyException e) {
                log.info("generated code collided, retrying (attempt {})", attempt);
            }
        }
        throw new IllegalStateException("could not generate a free code in " + MAX_CODE_ATTEMPTS + " attempts");
    }

    private Instant checkExpiry(Instant expiresAt, Instant now) {
        if (expiresAt == null) {
            return null;
        }
        if (!expiresAt.isAfter(now)) {
            throw ApiException.badRequest("invalid-expiry", "expiresAt must be in the future");
        }
        if (expiresAt.isAfter(now.plus(maxExpiry))) {
            throw ApiException.badRequest("invalid-expiry", "expiresAt must be within " + maxExpiry.toDays() + " days");
        }
        return expiresAt.truncatedTo(ChronoUnit.MICROS);
    }
}
