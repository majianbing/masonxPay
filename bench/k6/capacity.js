/**
 * MasonXPay — Capacity Proof (open-model)
 * ─────────────────────────────────────────────────────────────────────────────
 * Unlike script.js (closed-model VUs + think-time, good for SLA spot checks),
 * this drives an OPEN model — a fixed ARRIVAL RATE of charges per second,
 * independent of how fast the system responds. That is the only way to measure
 * true capacity: if the system slows, offered load does NOT back off, so the
 * queue (and the real ceiling) becomes visible. Closed models hide this via
 * coordinated omission.
 *
 * Unit under test = one CHARGE = create payment-intent + confirm (the charge).
 * We drive charges/sec; the create is part of the charge cost and is included.
 *
 * Run ONE phase per invocation via SCENARIO (each phase isolates a question):
 *   SCENARIO=warmup  rate=20  duration=2m    JVM/JIT warm-up — DISCARD from results
 *   SCENARIO=ramp    → ramps arrival rate to RAMP_TO, find the knee
 *   SCENARIO=soak    rate=TARGET_RATE for DURATION (hours) — the headline number
 *   SCENARIO=spike   rate=PEAK_RATE (default 1000) — peak burst
 *
 * Gate (soak): cap_create_ms p99 < 100ms AND cap_system_errors rate < 0.1%.
 * Injected connector faults (default SIMULATOR_SUCCESS_RATE_PERCENT=91.37, i.e.
 * a realistic ~8.6% PSP decline rate) are STIMULUS — the system must absorb them
 * gracefully (a FAILED payment, not a 5xx). They are tracked separately as
 * `declines`, NOT counted as system errors. Because declines are ~8.6% of
 * traffic (>1%), they dominate the blended confirm/e2e p99 — cap_confirm_ms is
 * split into cap_confirm_success_ms / cap_confirm_decline_ms so the success-path
 * latency (the number the 500ms framing in CAPACITY.md refers to) stays visible
 * underneath the decline-tail-driven blended figure.
 *
 * Env:
 *   BASE_URL        e.g. http://<M1-wired-ip>:8088   (the nginx ALB, off-box)
 *   RUN_MODE        label: postgres_only | infra
 *   SCENARIO        warmup | ramp | soak | spike      (default soak)
 *   TARGET_RATE     charges/sec for soak              (default 100)
 *   PEAK_RATE       charges/sec for spike             (default 1000)
 *   RAMP_TO         charges/sec to ramp toward        (default 1200)
 *   DURATION        soak duration                     (default 30m)
 *   MERCHANT_COUNT  synthetic merchants to spread load (default 20)
 *   PRE_VUS/MAX_VUS k6 VU pool (Little's law: rate × latency)  (defaults below)
 */

import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Trend, Rate, Counter } from 'k6/metrics';

const BASE_URL       = __ENV.BASE_URL || 'http://localhost:8088';
const RUN_MODE       = __ENV.RUN_MODE || 'postgres_only';
const SCENARIO       = __ENV.SCENARIO || 'soak';
const TARGET_RATE    = Number(__ENV.TARGET_RATE || '100');
const PEAK_RATE      = Number(__ENV.PEAK_RATE || '1000');
const RAMP_TO        = Number(__ENV.RAMP_TO || '1200');
const DURATION       = __ENV.DURATION || '30m';
const MERCHANT_COUNT = Number(__ENV.MERCHANT_COUNT || '20');
const SIM_SUCCESS_PCT = Number(__ENV.SIMULATOR_SUCCESS_RATE_PERCENT || '91.37');

// VU pool sizing (Little's law: concurrency ≈ rate × per-charge seconds).
// A charge ≈ create + confirm ≈ ~0.5s end-to-end, so ~rate/2 in-flight; size up.
const PRE_VUS = Number(__ENV.PRE_VUS || String(Math.max(50, Math.ceil(TARGET_RATE))));
const MAX_VUS = Number(__ENV.MAX_VUS || String(Math.max(300, Math.ceil(PEAK_RATE * 2))));

// ── Metrics ──────────────────────────────────────────────────────────────────
const createMs   = new Trend('cap_create_ms', true);
const confirmMs  = new Trend('cap_confirm_ms', true);   // the charge endpoint — blended (success + decline)
const confirmSuccessMs = new Trend('cap_confirm_success_ms', true); // confirm latency, SUCCEEDED only
const confirmDeclineMs = new Trend('cap_confirm_decline_ms', true); // confirm latency, graceful FAILED only
const chargeE2eMs = new Trend('cap_charge_e2e_ms', true); // create+confirm wall time (merchant view)
const sysErrors  = new Rate('cap_system_errors');        // 5xx / network / non-2xx — GATED
const declines   = new Rate('cap_declines');             // graceful FAILED payments (stimulus, not error)
const rateLimited = new Counter('cap_rate_limited_429');

// ── Scenario selection (one phase per run) ───────────────────────────────────
function buildScenario() {
  const common = { exec: 'charge', tags: { scenario: SCENARIO, runtime: RUN_MODE } };
  switch (SCENARIO) {
    case 'warmup':
      return { warmup: { executor: 'constant-arrival-rate', rate: 20, timeUnit: '1s',
        duration: '2m', preAllocatedVUs: 50, maxVUs: 200, ...common } };
    case 'ramp':
      return { ramp: { executor: 'ramping-arrival-rate', startRate: 20, timeUnit: '1s',
        preAllocatedVUs: PRE_VUS, maxVUs: MAX_VUS,
        stages: [
          { target: Math.ceil(RAMP_TO * 0.25), duration: '2m' },
          { target: Math.ceil(RAMP_TO * 0.50), duration: '2m' },
          { target: Math.ceil(RAMP_TO * 0.75), duration: '2m' },
          { target: RAMP_TO,                    duration: '2m' },
          { target: RAMP_TO,                    duration: '2m' },
        ], ...common } };
    case 'spike':
      return { spike: { executor: 'constant-arrival-rate', rate: PEAK_RATE, timeUnit: '1s',
        duration: __ENV.DURATION || '3m', preAllocatedVUs: Math.ceil(PEAK_RATE), maxVUs: MAX_VUS, ...common } };
    case 'soak':
    default:
      return { soak: { executor: 'constant-arrival-rate', rate: TARGET_RATE, timeUnit: '1s',
        duration: DURATION, preAllocatedVUs: PRE_VUS, maxVUs: MAX_VUS, ...common } };
  }
}

export const options = {
  scenarios: buildScenario(),
  // Gate only the soak (the headline). ramp/spike are exploratory; warmup is discarded.
  //
  // Capacity gate = SYSTEM health, NOT merchant-view latency:
  //   • cap_create_ms is pure platform/DB (create makes NO connector call), so its
  //     p99 is the clean saturation signal — it climbs only when the platform itself
  //     (pool / PG / CPU) is under pressure.
  //   • cap_system_errors catches confirm-path failures.
  // The merchant-view confirm p99 is connector-latency-bound (≈ connector p99 380ms +
  // platform), so it is REPORTED via summaryTrendStats — never gated here.
  // cap_confirm_ms blends success + decline latency; at the realistic ~8.6% decline
  // rate, declines dominate its p99. cap_confirm_success_ms / cap_confirm_decline_ms
  // split the two populations so the success-path number stays legible.
  thresholds: SCENARIO === 'soak' ? {
    'cap_create_ms': ['p(99)<100'],
    'cap_system_errors': ['rate<0.001'],
  } : {
    // Keep the run alive but record everything for the other phases.
    'cap_system_errors': ['rate<1'],
  },
  // k6's default summary stops at p95 — add p99/p99.9 so the gated metric is visible.
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)', 'max'],
  // Don't let a slow tail wedge VUs forever.
  setupTimeout: '120s',
};

// ── setup(): build a POOL of merchants so per-merchant rate limits don't cap us ─
export function setup() {
  const runId = Date.now();
  const jsonHdr = { 'Content-Type': 'application/json' };
  const merchants = [];

  for (let m = 0; m < MERCHANT_COUNT; m++) {
    const email = `cap-${runId}-${m}@benchmark.local`;
    const reg = http.post(`${BASE_URL}/api/v1/auth/register`,
      JSON.stringify({ email, password: 'BenchPass123!', merchantName: `cap-${runId}-${m}` }),
      { headers: jsonHdr });
    if (reg.status !== 201) { throw new Error(`setup: register ${m} failed ${reg.status} — ${reg.body}`); }
    const body = reg.json();
    const accessToken = body.accessToken;
    const merchantId = body.memberships[0].merchants[0].merchantId;
    const authHdr = { 'Authorization': `Bearer ${accessToken}` };

    const keyRes = http.post(`${BASE_URL}/api/v1/merchants/${merchantId}/api-keys`,
      JSON.stringify({ name: 'cap-key', mode: 'TEST' }),
      { headers: Object.assign({}, jsonHdr, authHdr) });
    if (keyRes.status !== 201) { throw new Error(`setup: api-key ${m} failed ${keyRes.status} — ${keyRes.body}`); }
    const secretKey = keyRes.json().secretKey.secretPlaintext;

    const conn = http.post(`${BASE_URL}/api/v1/merchants/${merchantId}/connectors`,
      JSON.stringify({ provider: 'SIMULATOR', mode: 'TEST', label: `Sim ${runId}-${m}`,
        primary: true, weight: 100, fixedFeeCents: 0, rateBps: 0,
        simulatorSuccessRatePercent: SIM_SUCCESS_PCT }),
      { headers: Object.assign({}, jsonHdr, authHdr) });
    if (conn.status !== 201) { throw new Error(`setup: connector ${m} failed ${conn.status} — ${conn.body}`); }

    merchants.push({ merchantId, secretKey });
  }

  console.log(`setup: ${merchants.length} merchants, scenario=${SCENARIO}, runMode=${RUN_MODE}, ` +
    `target=${TARGET_RATE}/s peak=${PEAK_RATE}/s runId=${runId}`);
  return { merchants, runId };
}

// ── charge(): one create + confirm, driven at the scenario's arrival rate ─────
export function charge(data) {
  const { merchants, runId } = data;
  // Spread across merchants so no single merchant hits a per-merchant limit.
  const m = merchants[(exec.vu.idInTest + exec.scenario.iterationInInstance) % merchants.length];
  const jsonHdr = { 'Content-Type': 'application/json' };
  const apiKeyHdr = { 'Authorization': `Bearer ${m.secretKey}` };
  const tags = { runtime: RUN_MODE };

  const idemKey = `cap-${runId}-${SCENARIO}-vu${exec.vu.idInTest}-it${exec.scenario.iterationInInstance}`;
  const t0 = Date.now();

  // 1) Create the intent.
  const created = http.post(`${BASE_URL}/api/v1/payment-intents`,
    JSON.stringify({ amount: 2500, currency: 'usd', idempotencyKey: idemKey }),
    { headers: Object.assign({}, jsonHdr, apiKeyHdr), tags: Object.assign({ op: 'create' }, tags) });
  createMs.add(created.timings.duration);
  if (created.status === 429) { rateLimited.add(1); sysErrors.add(true); return; }
  if (created.status !== 201) { sysErrors.add(true); return; }
  const piId = created.json('id');

  // 2) Confirm = the charge. This is the GATED endpoint.
  const confirmed = http.post(`${BASE_URL}/api/v1/payment-intents/${piId}/confirm`,
    JSON.stringify({ paymentMethodId: 'sim_pm_card_visa', paymentMethodType: 'card' }),
    { headers: Object.assign({}, jsonHdr, apiKeyHdr), tags: Object.assign({ op: 'confirm' }, tags) });
  confirmMs.add(confirmed.timings.duration);
  chargeE2eMs.add(Date.now() - t0);

  if (confirmed.status === 429) { rateLimited.add(1); sysErrors.add(true); return; }
  if (confirmed.status >= 500 || confirmed.status === 0) { sysErrors.add(true); return; }

  // 2xx: the platform handled it. SUCCEEDED = clean charge; anything else (e.g. a
  // FAILED from an injected connector fault) is a graceful decline — stimulus, not
  // a system error.
  const okHttp = check(confirmed, { 'confirm → 2xx': (r) => r.status >= 200 && r.status < 300 });
  sysErrors.add(!okHttp);
  if (okHttp) {
    const succeeded = confirmed.json('status') === 'SUCCEEDED';
    declines.add(!succeeded);
    if (succeeded) {
      confirmSuccessMs.add(confirmed.timings.duration);
    } else {
      confirmDeclineMs.add(confirmed.timings.duration);
    }
  }
}
