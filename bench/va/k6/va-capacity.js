/**
 * MasonXPay VA Service — Capacity Bench (open-model)
 * ─────────────────────────────────────────────────────────────────────────────
 * Unlike the gateway bench, the VA service has no connector call. Each posting
 * is a pure DB transaction: SELECT FOR UPDATE on 2 accounts + 2 INSERTs +
 * 2 UPDATEs + HMAC-SHA256 × 2 entries. Expected hold time: 3–8ms.
 *
 * Little's law comparison:
 *   gateway  80 conns ÷ 420ms hold ≈  190 TPS ceiling  (connector-hold bottleneck)
 *   VA       40 conns ÷   5ms hold ≈ 8,000 TPS theoretical max
 * The real VA ceiling is Postgres WAL throughput / I/O, not the pool.
 *
 * Correctness is the key VA-specific invariant absent from the gateway bench:
 * under any concurrency, each account's balance must equal the sum of its
 * ledger entries, entry_seq must be gapless (1,2,3…N), and the HMAC chain
 * must be unbroken. The 'correctness' scenario verifies all three invariants
 * after a fixed-iteration hotspot run via GET /internal/bench/verify/{id}.
 *
 * Scenarios (one per run via SCENARIO env):
 *   warmup      20/s 2m             JVM warm-up — discard results
 *   ramp        20 → RAMP_TO/s      find the throughput knee
 *   soak        TARGET_RATE/s DURATION  headline number (gated: p99 ≤ 50ms + errors < 0.1%)
 *   spike       PEAK_RATE/s 3m      burst tolerance
 *   correctness 50 VUs shared TOTAL_ITERS all→pair 0, then teardown verifies
 *
 * Contention modes (CONTENTION env):
 *   spread   VU n uses pair (n % PAIR_COUNT)  ← default for soak/ramp/spike
 *   hotspot  all VUs use pair 0               ← default for correctness
 *
 * Env:
 *   BASE_URL      http://localhost:8087        VA service host (bench endpoints)
 *   SCENARIO      warmup|ramp|soak|spike|correctness  (default soak)
 *   TARGET_RATE   postings/sec for soak              (default 150)
 *   RAMP_TO       ceiling for ramp                   (default 2000)
 *   PEAK_RATE     rate for spike                     (default 800)
 *   DURATION      soak duration                      (default 30m)
 *   PAIR_COUNT    account pairs created at setup      (default 100)
 *   CONTENTION    spread|hotspot                     (see above)
 *   TOTAL_ITERS   shared iterations for correctness  (default 5000)
 *   AMOUNT        posting amount USD                 (default 10.00)
 */

import http from 'k6/http';
import {check} from 'k6';
import exec from 'k6/execution';
import {Trend, Rate} from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8087';
const SCENARIO = __ENV.SCENARIO || 'soak';
const TARGET_RATE = Number(__ENV.TARGET_RATE || '200');
const RAMP_TO = Number(__ENV.RAMP_TO || '3000');
const PEAK_RATE = Number(__ENV.PEAK_RATE || '1000');
const DURATION = __ENV.DURATION || '1h';
const PAIR_COUNT = Number(__ENV.PAIR_COUNT || '100');
const CONTENTION = __ENV.CONTENTION || (SCENARIO === 'correctness' ? 'hotspot' : 'spread');
const TOTAL_ITERS = Number(__ENV.TOTAL_ITERS || '5000');
const AMOUNT = __ENV.AMOUNT || '10.00';

// VU pool: at 5ms hold Little's law needs far fewer VUs than the gateway.
// Size PRE_VUS conservatively; MAX_VUS covers the ramp ceiling.
const PRE_VUS = Number(__ENV.PRE_VUS || String(Math.max(10, Math.ceil(TARGET_RATE * 0.05))));
const MAX_VUS = Number(__ENV.MAX_VUS || '500');

// ── Metrics ──────────────────────────────────────────────────────────────────
const postMs = new Trend('va_post_ms', true);  // posting latency — GATED soak p99 ≤ 50ms
const sysErrors = new Rate('va_sys_errors');       // 5xx / network — GATED < 0.1%

// ── Scenario selection ────────────────────────────────────────────────────────
function buildScenario() {
    const common = {exec: 'post', tags: {scenario: SCENARIO, contention: CONTENTION}};
    switch (SCENARIO) {
        case 'warmup':
            return {
                warmup: {
                    executor: 'constant-arrival-rate', rate: 20, timeUnit: '1s',
                    duration: '2m', preAllocatedVUs: 10, maxVUs: 50, ...common
                }
            };

        case 'ramp':
            return {
                ramp: {
                    executor: 'ramping-arrival-rate', startRate: 20, timeUnit: '1s',
                    preAllocatedVUs: PRE_VUS, maxVUs: MAX_VUS,
                    stages: [
                        {target: Math.ceil(RAMP_TO * 0.10), duration: '2m'},
                        {target: Math.ceil(RAMP_TO * 0.25), duration: '2m'},
                        {target: Math.ceil(RAMP_TO * 0.50), duration: '2m'},
                        {target: Math.ceil(RAMP_TO * 0.75), duration: '2m'},
                        {target: RAMP_TO, duration: '2m'},
                        {target: RAMP_TO, duration: '3m'},  // sustain at ceiling
                    ], ...common
                }
            };

        case 'spike':
            return {
                spike: {
                    executor: 'constant-arrival-rate', rate: PEAK_RATE, timeUnit: '1s',
                    duration: __ENV.DURATION || '5m',
                    preAllocatedVUs: Math.max(20, Math.ceil(PEAK_RATE * 0.05)), maxVUs: MAX_VUS, ...common
                }
            };

        case 'correctness':
            // Fixed total iterations (not rate-based) so we know the exact expected balance.
            return {
                correctness: {
                    executor: 'shared-iterations', vus: 50,
                    iterations: TOTAL_ITERS, maxDuration: '10m', ...common
                }
            };

        case 'soak':
        default:
            return {
                soak: {
                    executor: 'constant-arrival-rate', rate: TARGET_RATE, timeUnit: '1s',
                    duration: DURATION, preAllocatedVUs: PRE_VUS, maxVUs: MAX_VUS, ...common
                }
            };
    }
}

export const options = {
    scenarios: buildScenario(),
    // Gate only the soak (the headline number). Ramp/spike/correctness are exploratory.
    // 50ms is tighter than gateway's 100ms — justified because VA has no connector call.
    thresholds: SCENARIO === 'soak' ? {
        'va_post_ms': ['p(99)<50'],
        'va_sys_errors': ['rate<0.001'],
    } : {
        'va_sys_errors': ['rate<1'],   // keep run alive, record everything
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)', 'max'],
    setupTimeout: '60s',
};

// ── setup(): create N account pairs via the bench endpoint ───────────────────
export function setup() {
    const res = http.post(`${BASE_URL}/internal/bench/setup`,
        JSON.stringify({pairCount: PAIR_COUNT}),
        {headers: {'Content-Type': 'application/json'}});

    if (res.status !== 200) {
        throw new Error(`setup failed ${res.status}: ${res.body}`);
    }

    const data = res.json();
    console.log(`setup: runId=${data.runId} pairs=${data.pairs.length} ` +
        `scenario=${SCENARIO} contention=${CONTENTION} ` +
        `target=${TARGET_RATE}/s amount=${AMOUNT}`);
    return {pairs: data.pairs, runId: data.runId};
}

// ── post(): one 2-entry balanced posting, driven at the scenario arrival rate ─
export function post(data) {
    const {pairs} = data;

    // spread: each VU uses its own pair (minimises lock contention — baseline throughput).
    // hotspot: all VUs hammer pair 0 (worst-case SELECT FOR UPDATE serialization).
    const pairIdx = CONTENTION === 'hotspot'
        ? 0
        : (exec.vu.idInTest + exec.scenario.iterationInInstance) % pairs.length;
    const pair = pairs[pairIdx];

    const res = http.post(`${BASE_URL}/internal/bench/post`,
        JSON.stringify({
            tenantAccountId: pair.tenantAccountId,
            externalAccountId: pair.externalAccountId,
            amount: AMOUNT,
        }),
        {
            headers: {'Content-Type': 'application/json'},
            tags: {pair: String(pairIdx)}
        });

    postMs.add(res.timings.duration);
    const ok = check(res, {'post → 200': (r) => r.status === 200});
    sysErrors.add(!ok);
}

// ── teardown(): verify correctness invariants after the run ──────────────────
// Skipped for warmup (discard run). Always runs for correctness scenario.
// For soak/ramp: spot-checks first 5 pairs as a sanity signal.
export function teardown(data) {
    if (SCENARIO === 'warmup') return;

    const pairsToVerify = SCENARIO === 'correctness'
        ? [data.pairs[0]]
        : data.pairs.slice(0, Math.min(5, data.pairs.length));

    console.log(`\nteardown: verifying ${pairsToVerify.length} account pair(s)...`);

    let allOk = true;
    for (const pair of pairsToVerify) {
        const res = http.get(`${BASE_URL}/internal/bench/verify/${pair.tenantAccountId}`);
        if (res.status !== 200) {
            console.error(`  VERIFY ERROR ${pair.tenantAccountId}: HTTP ${res.status}`);
            allOk = false;
            continue;
        }
        const v = res.json();
        const ok = v.balanceOk && v.seqOk && v.chainOk;
        const parts = [ok ? 'PASS' : `FAIL balance=${v.balanceOk} seq=${v.seqOk} chain=${v.chainOk}`];
        if (v.firstGapAtSeq) parts.push(`firstGap=${v.firstGapAtSeq}`);
        if (v.firstBrokenChainAtSeq) parts.push(`brokenChain=${v.firstBrokenChainAtSeq}`);
        console.log(`  [${pair.tenantAccountId}] entries=${v.entryCount} ${parts.join(' ')}`);
        if (!ok) allOk = false;
    }

    console.log(`\n=== CORRECTNESS: ${allOk ? 'ALL PASS ✓' : 'INVARIANTS VIOLATED ✗'} ===`);
}
