package com.agentic.shortener.link;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
public class LinkRepository {
    private final JdbcClient jdbc;

    public LinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a link and returns it with its id. */
    public Link insert(String code, String targetUrl, Instant createdAt, Instant expiresAt, String tokenHash) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO links (code, target_url, created_at, expires_at, token_hash)
                        VALUES (:code, :url, :createdAt, :expiresAt, :tokenHash)""")
                .param("code", code)
                .param("url", targetUrl)
                .param("createdAt", utc(createdAt))
                .param("expiresAt", utc(expiresAt))
                .param("tokenHash", tokenHash)
                .update(keys, "id");
        long id = keys.getKeyAs(Number.class).longValue();
        return new Link(id, code, targetUrl, createdAt, expiresAt, tokenHash);
    }

    public Optional<Link> findByCode(String code) {
        return jdbc.sql("SELECT id, code, target_url, created_at, expires_at, token_hash FROM links WHERE code = :code")
                .param("code", code)
                .query(LinkRepository::map)
                .optional();
    }

    /** Deletes links that expired before the cutoff; their click events go with them (ON DELETE CASCADE). */
    public int deleteExpiredBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM links WHERE expires_at IS NOT NULL AND expires_at < :cutoff")
                .param("cutoff", utc(cutoff))
                .update();
    }

    private static Link map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime expires = rs.getObject("expires_at", OffsetDateTime.class);
        return new Link(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("target_url"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                expires == null ? null : expires.toInstant(),
                rs.getString("token_hash"));
    }

    static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
