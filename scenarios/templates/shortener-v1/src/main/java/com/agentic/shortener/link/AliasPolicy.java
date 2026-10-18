package com.agentic.shortener.link;

import com.agentic.shortener.common.ApiException;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Custom aliases: 3 to 32 characters of [A-Za-z0-9_-], not a reserved word. Codes are case-sensitive. */
public final class AliasPolicy {
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{3,32}");

    private final Set<String> reserved;

    public AliasPolicy(List<String> reservedWords) {
        this.reserved = reservedWords.stream().map(w -> w.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    public void check(String alias) {
        if (!FORMAT.matcher(alias).matches()) {
            throw ApiException.badRequest("invalid-alias", "alias must be 3 to 32 characters of letters, digits, '_' or '-'");
        }
        if (isReserved(alias)) {
            throw ApiException.badRequest("reserved-alias", "alias '" + alias + "' is reserved");
        }
    }

    public boolean isReserved(String code) {
        return reserved.contains(code.toLowerCase(Locale.ROOT));
    }
}
