/**
 * MasonXPay Gateway — k6 Load Test
 *
 * Three named scenarios (all run by default, ~14 min total):
 *
 *   smoke        — 1 VU × 1 min          sanity check, all ops must succeed
 *   average_load — ramp 0→20 VUs, 8 min  models ~100 k tx/day sustained load
 *   spike        — ramp 0→100 VUs, 4 min stress ceiling
 *
 * Thresholds:
 *   p95 create PI  < 500 ms   (write: idempotency check + DB insert)
 *   p95 get PI     < 200 ms   (indexed primary-key read)
 *   p95 list PIs   < 300 ms   (paginated JPA Specification query)
 *   error rate     < 1%
 *
 * Mixed workload per iteration:
 *   50% — POST /api/v1/payment-intents          (API-key auth)
 *   30% — GET  /api/v1/payment-intents/:id      (API-key auth, seed data)
 *   20% — GET  /api/v1/merchants/:id/payment-intents  (JWT auth, paginated list)
 *
 * Environment variables:
 *   BASE_URL  — default http://localhost:8080  (http://backend:8080 inside Docker)
 */

import http  from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate }  from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Per-operation latency metrics (true = in milliseconds)
const createMs = new Trend('pi_create_ms', true);
const getMs    = new Trend('pi_get_ms',    true);
const listMs   = new Trend('pi_list_ms',   true);
const errorRate = new Rate('errors');

// ─────────────────────────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-vus',
      vus: 1,
      duration: '1m',
      exec: 'mixedWorkload',
      tags: { scenario: 'smoke' },
    },

    average_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '2m', target: 20 },   // ramp up
        { duration: '5m', target: 20 },   // sustain — ~1.5 req/s across 20 VUs
        { duration: '1m', target: 0  },   // ramp down
      ],
      startTime: '70s',                   // starts after smoke finishes
      exec: 'mixedWorkload',
      tags: { scenario: 'average_load' },
    },

    spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m',  target: 100 }, // aggressive ramp
        { duration: '2m',  target: 100 }, // sustain
        { duration: '30s', target: 0   }, // drop
      ],
      startTime: '11m',                   // starts after average_load finishes
      exec: 'mixedWorkload',
      tags: { scenario: 'spike' },
    },
  },

  thresholds: {
    // Global: zero errors across all scenarios
    errors:          ['rate<0.01'],
    http_req_failed: ['rate<0.01'],

    // smoke — 1 VU, should be fast (JVM warmed up after a few requests)
    'pi_create_ms{scenario:smoke}': ['p(95)<300'],
    'pi_get_ms{scenario:smoke}':    ['p(95)<100'],
    'pi_list_ms{scenario:smoke}':   ['p(95)<200'],

    // average_load — 20 VUs, ~100k tx/day sustained — production SLA
    'pi_create_ms{scenario:average_load}': ['p(95)<500'],
    'pi_get_ms{scenario:average_load}':    ['p(95)<200'],
    'pi_list_ms{scenario:average_load}':   ['p(95)<300'],

    // spike — 100 VUs — stress ceiling; passes as long as system stays alive
    // (thresholds here are generous — spike finds limits, not validates SLAs)
    'pi_create_ms{scenario:spike}': ['p(95)<3000'],
    'pi_get_ms{scenario:spike}':    ['p(95)<3000'],
    'pi_list_ms{scenario:spike}':   ['p(95)<3000'],
  },
};

// ─────────────────────────────────────────────────────────────────────────────
// setup() runs once before all VUs start.
// Returns data shared (read-only) across every VU iteration.
// ─────────────────────────────────────────────────────────────────────────────
export function setup() {
  const suffix   = Date.now();
  const email    = `bench-${suffix}@benchmark.local`;
  const password = 'BenchPass123!';
  const jsonHdr  = { 'Content-Type': 'application/json' };

  // ── 1. Register a dedicated benchmark merchant ───────────────────────────
  const regRes = http.post(
    `${BASE_URL}/api/v1/auth/register`,
    JSON.stringify({ email, password, merchantName: `bench-${suffix}` }),
    { headers: jsonHdr },
  );
  if (regRes.status !== 201) {
    throw new Error(`setup: register failed ${regRes.status} — ${regRes.body}`);
  }
  const regBody     = regRes.json();
  const accessToken = regBody.accessToken;
  // AuthResponse: memberships[].merchants[] (org → merchant nesting)
  const merchantId  = regBody.memberships[0].merchants[0].merchantId;
  const authHdr     = { 'Authorization': `Bearer ${accessToken}` };

  // ── 2. Create a TEST API key ─────────────────────────────────────────────
  const keyRes = http.post(
    `${BASE_URL}/api/v1/merchants/${merchantId}/api-keys`,
    JSON.stringify({ name: 'bench-key', mode: 'TEST' }),
    { headers: Object.assign({}, jsonHdr, authHdr) },
  );
  if (keyRes.status !== 201) {
    throw new Error(`setup: create API key failed ${keyRes.status} — ${keyRes.body}`);
  }
  // ApiKeyPairResponse: { secretKey: { secretPlaintext: "sk_test_..." }, publishableKey: { ... } }
  const secretKey  = keyRes.json().secretKey.secretPlaintext;
  const apiKeyHdr  = { 'Authorization': `Bearer ${secretKey}` };

  // ── 3. Pre-create 20 seed PIs for read-only iterations ──────────────────
  //    Using stable IDs avoids write contention in GET scenarios.
  const seedIds = [];
  for (let i = 0; i < 20; i++) {
    const piRes = http.post(
      `${BASE_URL}/api/v1/payment-intents`,
      JSON.stringify({
        amount:         1000 + i,
        currency:       'usd',
        idempotencyKey: `seed-${suffix}-${i}`,
      }),
      { headers: Object.assign({}, jsonHdr, apiKeyHdr) },
    );
    if (piRes.status === 201) {
      seedIds.push(piRes.json('id'));
    } else {
      console.error(`setup: seed PI ${i} failed ${piRes.status} — ${piRes.body}`);
    }
  }
  if (seedIds.length === 0) {
    throw new Error('setup: failed to pre-create any seed payment intents (see errors above)');
  }

  console.log(`setup: merchant=${merchantId}, seedPIs=${seedIds.length}`);
  return { merchantId, accessToken, secretKey, seedIds };
}

// ─────────────────────────────────────────────────────────────────────────────
// mixedWorkload — called by every VU in every scenario.
// data = value returned by setup().
// ─────────────────────────────────────────────────────────────────────────────
export function mixedWorkload(data) {
  const { merchantId, accessToken, secretKey, seedIds } = data;
  const jsonHdr   = { 'Content-Type': 'application/json' };
  const apiKeyHdr = { 'Authorization': `Bearer ${secretKey}` };
  const jwtHdr    = { 'Authorization': `Bearer ${accessToken}` };

  const r = Math.random();

  if (r < 0.50) {
    // ── 50% — Create a payment intent ──────────────────────────────────────
    // Exercises: API-key lookup → idempotency INSERT → PI insert → routing
    const idempKey = `bench-vu${__VU}-it${__ITER}`;
    const res = http.post(
      `${BASE_URL}/api/v1/payment-intents`,
      JSON.stringify({ amount: 2500, currency: 'usd', idempotencyKey: idempKey }),
      { headers: Object.assign({}, jsonHdr, apiKeyHdr), tags: { op: 'create_pi' } },
    );
    createMs.add(res.timings.duration);
    const ok = check(res, { 'create PI → 201': (r) => r.status === 201 });
    errorRate.add(!ok);

  } else if (r < 0.80) {
    // ── 30% — Get a single payment intent by ID ────────────────────────────
    // Exercises: API-key lookup → primary-key read (should hit Postgres buffer cache)
    const piId = seedIds[__VU % seedIds.length];
    const res = http.get(
      `${BASE_URL}/api/v1/payment-intents/${piId}`,
      { headers: apiKeyHdr, tags: { op: 'get_pi' } },
    );
    getMs.add(res.timings.duration);
    const ok = check(res, { 'get PI → 200': (r) => r.status === 200 });
    errorRate.add(!ok);

  } else {
    // ── 20% — List payment intents (dashboard path, JWT auth) ──────────────
    // Exercises: JWT verify → RBAC check → JPA Specification query + pagination
    const res = http.get(
      `${BASE_URL}/api/v1/merchants/${merchantId}/payment-intents?mode=TEST&size=20`,
      { headers: jwtHdr, tags: { op: 'list_pi' } },
    );
    listMs.add(res.timings.duration);
    const ok = check(res, { 'list PIs → 200': (r) => r.status === 200 });
    errorRate.add(!ok);
  }

  // 100 ms think time keeps the load model realistic and prevents flooding
  sleep(0.1);
}
