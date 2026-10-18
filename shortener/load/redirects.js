// k6 load test for the redirect path. Not run by the Maven build.
//
//   RATE_LIMIT=1000 docker compose --profile shortener up -d shortener
//   docker run --rm -i --network host grafana/k6 run - < shortener/load/redirects.js
//
// Target from the requirement: 1,000 redirects/s on one node, p99 under 50 ms, under 0.1% errors.
// Run it once with Redis up and once after `docker compose stop redis` to check the fallback.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8081';

export const options = {
  scenarios: {
    redirects: {
      executor: 'constant-arrival-rate',
      rate: 1000,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 100,
      maxVUs: 400,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    http_req_duration: ['p(99)<50'],
  },
};

export function setup() {
  const codes = [];
  for (let i = 0; i < 50; i++) {
    // All setup requests come from one client: start the service with RATE_LIMIT=1000 (above).
    const res = http.post(`${BASE}/api/links`, JSON.stringify({ url: `https://example.com/k6/${i}` }),
      { headers: { 'Content-Type': 'application/json' } });
    if (res.status === 201) codes.push(res.json('code'));
  }
  if (codes.length === 0) throw new Error('could not create any links');
  return { codes };
}

export default function (data) {
  const code = data.codes[Math.floor(Math.random() * data.codes.length)];
  const res = http.get(`${BASE}/${code}`, { redirects: 0 });
  check(res, { 'is 302': (r) => r.status === 302 });
}
