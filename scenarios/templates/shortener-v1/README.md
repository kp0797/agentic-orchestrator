# URL shortener (v1)

## Run it

```bash
mvn spring-boot:run      # http://localhost:8081; needs PostgreSQL (DB_URL) and optionally Redis
```

```bash
curl -s -X POST localhost:8081/api/links -H 'Content-Type: application/json' \
     -d '{"url":"https://example.com/a/long/path","alias":"my-link","expiresAt":"2027-01-01T00:00:00Z"}'
# 201 {"code":"my-link","shortUrl":"http://localhost:8081/my-link",...,"managementToken":"..."}

curl -si localhost:8081/my-link                       # 302 Location: https://example.com/a/long/path
curl -s localhost:8081/api/links/my-link/stats -H "X-Management-Token: <token>"
```

## API

| Method and path | Result |
| --- | --- |
| `POST /api/links` `{url, alias?, expiresAt?}` | 201 with `code`, `shortUrl`, `managementToken` (shown once). 400 invalid or unsafe URL, bad alias, bad expiry. 409 alias taken. 429 over 20 creates/min per client, with `Retry-After`. |
| `GET /{code}` | 302 to the target (`Cache-Control: no-store`). 404 unknown. 410 expired. |
| `GET /api/links/{code}/stats?days=30` | Needs `X-Management-Token`. Total clicks, clicks per UTC day (zero days included, at most 90), top 10 referrer hosts (`(direct)` for none). 401 without a valid token. |
| `GET /actuator/health`, `/actuator/prometheus` | Health probes and metrics. |

Errors are RFC 9457 problem details with a stable `type` (`.../problems/alias-taken` and so on).

## Design decisions

| Decision | Why |
| --- | --- |
| Random 7-character base62 codes, checked for use before insert | Not enumerable; 3.5 trillion codes. |
| SSRF check at creation: http/https only, no user info, every resolved address public | Blocks loopback, private, link-local (cloud metadata), CGNAT, ULA, NAT64/mapped forms. DNS behind a `HostResolver` port so tests never use the network. |
| Management token per link, only its SHA-256 stored | Stats are private without user accounts; a database leak does not leak tokens. Constant-time comparison. |
| Redis read-through cache behind a `LinkCache` port, with a circuit breaker | Redirects keep working, from the database, when Redis is down; an open breaker skips Redis instead of waiting on timeouts. |
| Clicks recorded asynchronously through a bounded queue | Redirects never wait for a write. When the queue is full, clicks are dropped and counted (`shortener.clicks.dropped`). |
| 302, not 301 | Browsers cache 301s forever, which would hide clicks and outlive expiry. |
| Only the referrer host is stored | Paths and query strings can carry personal data. |
| 90-day retention | Daily job deletes click events older than 90 days and links expired more than 90 days ago. |

## Limitations (v1)

- URL safety is checked at creation only; a host re-pointed later (DNS rebinding) is not re-checked.
- The rate limit is per node and keyed by client IP; behind a proxy set `FORWARD_HEADERS=native`.
- Click statistics are best-effort: clicks still queued when the process dies are lost.
- No link editing or deletion API yet.

## Tests

`mvn verify` runs everything on H2 (PostgreSQL mode) with MockMvc and fake DNS: no
Docker, no network. The load target (1,000 redirects/s) is checked with `load/redirects.js` (k6), which the
build does not run.
