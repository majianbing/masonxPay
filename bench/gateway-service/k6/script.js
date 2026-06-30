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
 *   p95 create PI      < 500 ms   (write: idempotency registry + sharded PI insert)
 *   p95 confirm PI     < 800 ms   (routing + simulator provider call + state/outbox write)
 *   p95 refund         < 800 ms   (refund guard + simulator provider call + state/outbox write)
 *   p95 idempotency    < 250 ms   (duplicate create, DB registry or Redis cache fast path)
 *   p95 get PI         < 200 ms   (indexed primary-key read)
 *   p95 list PIs       < 300 ms   (dashboard list: read model or authoritative fallback)
 *   error rate         < 1%
 *
 * Mixed workload per iteration:
 *   35% — POST /api/v1/payment-intents          (API-key auth, new idempotency key)
 *   10% — create + confirm with SIMULATOR PSP
 *    5% — create + confirm + partial refund with SIMULATOR PSP
 *   15% — POST /api/v1/payment-intents          (API-key auth, duplicate idempotency key)
 *   20% — GET  /api/v1/payment-intents/:id      (API-key auth, seed data)
 *   15% — GET  /api/v1/merchants/:id/payment-intents  (JWT auth, paginated list)
 *
 * Environment variables:
 *   BASE_URL  — default http://localhost:8080  (http://gateway-service:8080 inside Docker)
 *   RUN_MODE  — label for output grouping, e.g. postgres_only or preview_infra
 */

import http  from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Trend, Rate }  from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RUN_MODE = __ENV.RUN_MODE || 'postgres_only';
const SIMULATOR_SUCCESS_RATE_PERCENT = Number(__ENV.SIMULATOR_SUCCESS_RATE_PERCENT || '100');

// Per-operation latency metrics (true = in milliseconds)
const createMs      = new Trend('pi_create_ms', true);
const confirmMs     = new Trend('pi_confirm_ms', true);
const refundMs      = new Trend('pi_refund_ms', true);
const idempotencyMs = new Trend('pi_idempotency_ms', true);
const getMs         = new Trend('pi_get_ms', true);
const listMs        = new Trend('pi_list_ms', true);
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
    'pi_create_ms{scenario:smoke}':      ['p(95)<300'],
    'pi_confirm_ms{scenario:smoke}':     ['p(95)<600'],
    'pi_refund_ms{scenario:smoke}':      ['p(95)<700'],
    'pi_idempotency_ms{scenario:smoke}': ['p(95)<150'],
    'pi_get_ms{scenario:smoke}':         ['p(95)<100'],
    'pi_list_ms{scenario:smoke}':        ['p(95)<200'],

    // average_load — 20 VUs, ~100k tx/day sustained — production SLA
    'pi_create_ms{scenario:average_load}':      ['p(95)<500'],
    'pi_confirm_ms{scenario:average_load}':     ['p(95)<800'],
    'pi_refund_ms{scenario:average_load}':      ['p(95)<800'],
    'pi_idempotency_ms{scenario:average_load}': ['p(95)<250'],
    'pi_get_ms{scenario:average_load}':         ['p(95)<200'],
    'pi_list_ms{scenario:average_load}':        ['p(95)<300'],

    // spike — 100 VUs — stress ceiling; passes as long as system stays alive
    // (thresholds here are generous — spike finds limits, not validates SLAs)
    'pi_create_ms{scenario:spike}':      ['p(95)<3000'],
    'pi_confirm_ms{scenario:spike}':     ['p(95)<3000'],
    'pi_refund_ms{scenario:spike}':      ['p(95)<3000'],
    'pi_idempotency_ms{scenario:spike}': ['p(95)<3000'],
    'pi_get_ms{scenario:spike}':         ['p(95)<3000'],
    'pi_list_ms{scenario:spike}':        ['p(95)<3000'],
  },
};

// ─────────────────────────────────────────────────────────────────────────────
// setup() runs once before all VUs start.
// Returns data shared (read-only) across every VU iteration.
// ─────────────────────────────────────────────────────────────────────────────
export function setup() {
  const runId    = Date.now();
  const email    = `bench-${runId}@benchmark.local`;
  const password = 'BenchPass123!';
  const jsonHdr  = { 'Content-Type': 'application/json' };

  // ── 1. Register a dedicated benchmark merchant ───────────────────────────
  const regRes = http.post(
    `${BASE_URL}/api/v1/auth/register`,
    JSON.stringify({ email, password, merchantName: `bench-${runId}` }),
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

  // ── 3. Add the Mason Simulator connector ─────────────────────────────────
  //    This is a real provider account in TEST mode, so confirm/refund traffic
  //    goes through routing, credential loading, provider dispatch, and outbox.
  const connectorRes = http.post(
    `${BASE_URL}/api/v1/merchants/${merchantId}/connectors`,
    JSON.stringify({
      provider: 'SIMULATOR',
      mode: 'TEST',
      label: `Mason Simulator ${runId}`,
      primary: true,
      weight: 100,
      fixedFeeCents: 0,
      rateBps: 0,
      simulatorSuccessRatePercent: SIMULATOR_SUCCESS_RATE_PERCENT,
    }),
    { headers: Object.assign({}, jsonHdr, authHdr) },
  );
  if (connectorRes.status !== 201) {
    throw new Error(`setup: create simulator connector failed ${connectorRes.status} — ${connectorRes.body}`);
  }

  // ── 4. Pre-create 20 seed PIs for read/idempotency iterations ───────────
  //    Stable IDs avoid write contention in GET scenarios. Reusing their
  //    idempotency keys exercises the Postgres registry and Redis cache path.
  const seedIds = [];
  const seedIdempotencyKeys = [];
  for (let i = 0; i < 20; i++) {
    const idempotencyKey = `seed-${runId}-${i}`;
    const piRes = http.post(
      `${BASE_URL}/api/v1/payment-intents`,
      JSON.stringify({
        amount:         1000 + i,
        currency:       'usd',
        idempotencyKey,
      }),
      { headers: Object.assign({}, jsonHdr, apiKeyHdr) },
    );
    if (piRes.status === 201) {
      seedIds.push(piRes.json('id'));
      seedIdempotencyKeys.push(idempotencyKey);
    } else {
      console.error(`setup: seed PI ${i} failed ${piRes.status} — ${piRes.body}`);
    }
  }
  if (seedIds.length === 0) {
    throw new Error('setup: failed to pre-create any seed payment intents (see errors above)');
  }

  console.log(`setup: merchant=${merchantId}, seedPIs=${seedIds.length}, runMode=${RUN_MODE}, runId=${runId}`);
  return { merchantId, accessToken, secretKey, seedIds, seedIdempotencyKeys, runId };
}

// ─────────────────────────────────────────────────────────────────────────────
// mixedWorkload — called by every VU in every scenario.
// data = value returned by setup().
// ─────────────────────────────────────────────────────────────────────────────
export function mixedWorkload(data) {
  const { merchantId, accessToken, secretKey, seedIds, seedIdempotencyKeys, runId } = data;
  const jsonHdr   = { 'Content-Type': 'application/json' };
  const apiKeyHdr = { 'Authorization': `Bearer ${secretKey}` };
  const jwtHdr    = { 'Authorization': `Bearer ${accessToken}` };

  const r = Math.random();

  if (r < 0.35) {
    // ── 35% — Create a payment intent ──────────────────────────────────────
    // Exercises: API-key lookup → idempotency reservation → sharded PI insert.
    const idempKey = idempotencyKey(runId, 'create');
    const res = http.post(
      `${BASE_URL}/api/v1/payment-intents`,
      JSON.stringify({ amount: 2500, currency: 'usd', idempotencyKey: idempKey }),
      { headers: Object.assign({}, jsonHdr, apiKeyHdr), tags: { op: 'create_pi', runtime: RUN_MODE } },
    );
    createMs.add(res.timings.duration);
    const ok = check(res, { 'create PI → 201': (r) => r.status === 201 });
    errorRate.add(!ok);

  } else if (r < 0.45) {
    // ── 10% — Create then confirm through Mason Simulator ──────────────────
    const createKey = idempotencyKey(runId, 'confirm');
    const created = createPaymentIntent(apiKeyHdr, jsonHdr, createKey, 2600);
    if (!created.ok) {
      errorRate.add(true);
      return;
    }
    const res = http.post(
      `${BASE_URL}/api/v1/payment-intents/${created.id}/confirm`,
      JSON.stringify({ paymentMethodId: 'sim_pm_card_visa', paymentMethodType: 'card' }),
      { headers: Object.assign({}, jsonHdr, apiKeyHdr), tags: { op: 'confirm_pi', runtime: RUN_MODE } },
    );
    confirmMs.add(res.timings.duration);
    const ok = check(res, {
      'confirm PI → 200': (r) => r.status === 200,
      'confirm PI succeeded': (r) => r.json('status') === 'SUCCEEDED',
    });
    errorRate.add(!ok);

  } else if (r < 0.50) {
    // ── 5% — Create, confirm, then partially refund through simulator ──────
    const createKey = idempotencyKey(runId, 'refund');
    const created = createPaymentIntent(apiKeyHdr, jsonHdr, createKey, 3000);
    if (!created.ok) {
      errorRate.add(true);
      return;
    }
    const confirmed = http.post(
      `${BASE_URL}/api/v1/payment-intents/${created.id}/confirm`,
      JSON.stringify({ paymentMethodId: 'sim_pm_card_visa', paymentMethodType: 'card' }),
      { headers: Object.assign({}, jsonHdr, apiKeyHdr), tags: { op: 'confirm_before_refund', runtime: RUN_MODE } },
    );
    confirmMs.add(confirmed.timings.duration);
    if (confirmed.status !== 200 || confirmed.json('status') !== 'SUCCEEDED') {
      errorRate.add(true);
      return;
    }
    const res = http.post(
      `${BASE_URL}/api/v1/payment-intents/${created.id}/refunds`,
      JSON.stringify({ amount: 1000, reason: 'BENCHMARK' }),
      { headers: Object.assign({}, jsonHdr, apiKeyHdr), tags: { op: 'refund_pi', runtime: RUN_MODE } },
    );
    refundMs.add(res.timings.duration);
    const ok = check(res, {
      'refund PI → 201': (r) => r.status === 201,
      'refund PI succeeded': (r) => r.json('status') === 'SUCCEEDED',
    });
    errorRate.add(!ok);

  } else if (r < 0.65) {
    // ── 15% — Replay an existing idempotency key ───────────────────────────
    // Exercises: Redis route cache when enabled, otherwise the sharded DB
    // idempotency registry. The controller returns 201 for both first create
    // and duplicate replay, so correctness is checked through response shape.
    const i = (__VU + __ITER) % seedIdempotencyKeys.length;
    const res = http.post(
      `${BASE_URL}/api/v1/payment-intents`,
      JSON.stringify({ amount: 1000 + i, currency: 'usd', idempotencyKey: seedIdempotencyKeys[i] }),
      { headers: Object.assign({}, jsonHdr, apiKeyHdr), tags: { op: 'idempotency_replay', runtime: RUN_MODE } },
    );
    idempotencyMs.add(res.timings.duration);
    const ok = check(res, {
      'idempotency replay → 201': (r) => r.status === 201,
      'idempotency replay keeps id': (r) => r.json('id') === seedIds[i],
    });
    errorRate.add(!ok);

  } else if (r < 0.85) {
    // ── 20% — Get a single payment intent by ID ────────────────────────────
    // Exercises: API-key lookup → primary-key read routed to the owning shard.
    const piId = seedIds[__VU % seedIds.length];
    const res = http.get(
      `${BASE_URL}/api/v1/payment-intents/${piId}`,
      { headers: apiKeyHdr, tags: { op: 'get_pi', runtime: RUN_MODE } },
    );
    getMs.add(res.timings.duration);
    const ok = check(res, { 'get PI → 200': (r) => r.status === 200 });
    errorRate.add(!ok);

  } else {
    // ── 15% — List payment intents (dashboard path, JWT auth) ──────────────
    // Exercises: JWT verify → RBAC check → projection read model when Kafka is
    // enabled, or authoritative-table fallback when Kafka projection is off.
    const res = http.get(
      `${BASE_URL}/api/v1/merchants/${merchantId}/payment-intents?mode=TEST&size=20`,
      { headers: jwtHdr, tags: { op: 'list_pi', runtime: RUN_MODE } },
    );
    listMs.add(res.timings.duration);
    const ok = check(res, { 'list PIs → 200': (r) => r.status === 200 });
    errorRate.add(!ok);
  }

  // 100 ms think time keeps the load model realistic and prevents flooding
  sleep(0.1);
}

function createPaymentIntent(apiKeyHdr, jsonHdr, idempotencyKey, amount) {
  const res = http.post(
    `${BASE_URL}/api/v1/payment-intents`,
    JSON.stringify({ amount, currency: 'usd', idempotencyKey }),
    { headers: Object.assign({}, jsonHdr, apiKeyHdr), tags: { op: 'create_for_flow', runtime: RUN_MODE } },
  );
  createMs.add(res.timings.duration);
  return {
    ok: res.status === 201,
    id: res.status === 201 ? res.json('id') : null,
  };
}

function idempotencyKey(runId, operation) {
  // __VU and __ITER are scoped to the scenario, so include scenario + runId to
  // avoid cross-scenario collisions that can replay an already-confirmed intent.
  return `bench-${runId}-${exec.scenario.name}-${operation}-vu${__VU}-it${__ITER}`;
}
