/**
 * MasonXPay VA Service — Card Authorization Bench
 * ─────────────────────────────────────────────────────────────────────────────
 * Exercises the card authorization decision core (CardAuthorizationService)
 * through the rail-sim issuer adapter endpoint /internal/issuer/authorize,
 * end-to-end against a real Postgres ledger:
 *
 *   wallet (WALLET) ── fund ──► card (PREPAID_CARD) ── auth hold ──► (PREPAID_CARD_HOLD)
 *
 * The invariant under test is fix #1 (approve-without-hold):
 *   APPROVED  ⟹  exactly one hold journal, no matter how many times the same
 *   authorizationId is delivered, sequentially or concurrently.
 *
 * Scenarios (one per run via SCENARIO env):
 *   smoke         1 iteration functional walk: approve → replay → decline →
 *                 replay-decline → unknown card → contract validation.
 *                 The default; run after any change to the auth path.
 *   replay-storm  VUS concurrent VUs all deliver the SAME authorizationId.
 *                 Teardown proves the hold moved exactly once (frozen == AUTH_AMOUNT).
 *                 This is the concurrency proof of idempotent replay.
 *   auth-rate     TARGET_RATE/s distinct authorizations for DURATION.
 *                 Throughput of the full decision path (replay check + lock +
 *                 hold posting + decision record). Conservation checked at teardown:
 *                 balance + frozen == FUND_AMOUNT.
 *
 * Env:
 *   BASE_URL       http://localhost:8087    VA service (bench stack, see ../docker-compose.yml)
 *   SCENARIO       smoke|replay-storm|auth-rate   (default smoke)
 *   FUND_AMOUNT    card load at setup             (default 5000.00)
 *   AUTH_AMOUNT    per-auth amount                (default 1.00)
 *   TARGET_RATE    auths/sec for auth-rate        (default 50)
 *   DURATION       auth-rate duration             (default 1m)
 *   VUS            concurrent VUs for replay-storm (default 30)
 *   STORM_ITERS    total deliveries for replay-storm (default 500)
 *   INTERNAL_TOKEN X-Internal-Token value          (default internal-dev-secret)
 *
 * auth-rate sizing: FUND_AMOUNT must exceed TARGET_RATE × duration_seconds ×
 * AUTH_AMOUNT, otherwise the card runs dry and later auths decline with
 * INSUFFICIENT_FUNDS (declines are counted separately, not as errors).
 */

import http from 'k6/http';
import {check, fail} from 'k6';
import exec from 'k6/execution';
import {Trend, Rate, Counter} from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8087';
const SCENARIO = __ENV.SCENARIO || 'smoke';
const FUND_AMOUNT = __ENV.FUND_AMOUNT || '5000.00';
const AUTH_AMOUNT = __ENV.AUTH_AMOUNT || '1.00';
const TARGET_RATE = Number(__ENV.TARGET_RATE || '50');
const DURATION = __ENV.DURATION || '1m';
const VUS = Number(__ENV.VUS || '30');
const STORM_ITERS = Number(__ENV.STORM_ITERS || '500');
const INTERNAL_TOKEN = __ENV.INTERNAL_TOKEN || 'internal-dev-secret';

const JSON_HEADERS = {
    'Content-Type': 'application/json',
    'X-Internal-Token': INTERNAL_TOKEN,
};

// ── Metrics ──────────────────────────────────────────────────────────────────
const authMs = new Trend('card_auth_ms', true);
const sysErrors = new Rate('card_auth_sys_errors');   // 5xx / network / unexpected body
const approvals = new Counter('card_auth_approved');
const declines = new Counter('card_auth_declined');

// ── Scenario selection ────────────────────────────────────────────────────────
function buildScenario() {
    switch (SCENARIO) {
        case 'replay-storm':
            return {
                replayStorm: {
                    executor: 'shared-iterations', vus: VUS, iterations: STORM_ITERS,
                    maxDuration: '10m', exec: 'replayStorm', tags: {scenario: SCENARIO},
                }
            };
        case 'auth-rate':
            return {
                authRate: {
                    executor: 'constant-arrival-rate', rate: TARGET_RATE, timeUnit: '1s',
                    duration: DURATION, preAllocatedVUs: Math.max(10, Math.ceil(TARGET_RATE * 0.2)),
                    maxVUs: 200, exec: 'authRate', tags: {scenario: SCENARIO},
                }
            };
        case 'smoke':
        default:
            return {
                smoke: {
                    executor: 'shared-iterations', vus: 1, iterations: 1,
                    maxDuration: '2m', exec: 'smoke', tags: {scenario: SCENARIO},
                }
            };
    }
}

export const options = {
    scenarios: buildScenario(),
    thresholds: {
        'checks': ['rate==1'],              // every functional assertion must hold
        'card_auth_sys_errors': ['rate<0.001'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    setupTimeout: '90s',
};

// ── HTTP helpers ─────────────────────────────────────────────────────────────
function post(path, body) {
    return http.post(`${BASE_URL}${path}`, JSON.stringify(body), {headers: JSON_HEADERS});
}

function authorize(cardTokenId, authorizationId, amount) {
    const res = post('/internal/issuer/authorize', {
        authorizationId, cardTokenId, amount, currency: 'USD', stan: '000001', rrn: 'bench',
    });
    authMs.add(res.timings.duration);
    return res;
}

function getCard(cardId) {
    const res = http.get(`${BASE_URL}/v1/vcc/cards/${cardId}`, {headers: JSON_HEADERS});
    if (res.status !== 200) {
        fail(`getCard failed ${res.status}: ${res.body}`);
    }
    return res.json();
}

function near(a, b) {
    return Math.abs(Number(a) - Number(b)) < 0.001;
}

// ── setup(): wallet → card → funded card ─────────────────────────────────────
export function setup() {
    const runId = 'card' + Date.now();
    const merchantId = 'm_' + runId;

    // Counter-leg for wallet funding: one bench pair provides an EXTERNAL account.
    const pairRes = post('/internal/bench/setup', {pairCount: 1});
    if (pairRes.status !== 200) {
        throw new Error(`bench setup failed ${pairRes.status}: ${pairRes.body} ` +
            '(is the stack up with va.bench.enabled=true? see bench/va/docker-compose.yml)');
    }
    const externalId = pairRes.json().pairs[0].externalLedgerAccountId;

    // Merchant wallet.
    const walletRes = post('/internal/va/accounts', {
        merchantId, orgId: 'org_cardbench', ledgerAccountType: 'WALLET', asset: 'USD',
    });
    if (walletRes.status !== 200 && walletRes.status !== 201) {
        throw new Error(`wallet create failed ${walletRes.status}: ${walletRes.body}`);
    }
    const walletId = walletRes.json().ledgerAccountId;

    // Fund the wallet (DR wallet / CR external), then create + fund the card.
    const fundWallet = post('/internal/bench/post', {
        tenantLedgerAccountId: walletId, externalLedgerAccountId: externalId, amount: FUND_AMOUNT,
    });
    if (fundWallet.status !== 200) {
        throw new Error(`wallet funding failed ${fundWallet.status}: ${fundWallet.body}`);
    }

    const cardRes = post('/v1/vcc/cards', {
        merchantId, ownerAccountId: walletId, currency: 'USD',
    });
    if (cardRes.status !== 200 && cardRes.status !== 201) {
        throw new Error(`card create failed ${cardRes.status}: ${cardRes.body}`);
    }
    const cardId = cardRes.json().cardId;
    const cardTokenId = cardRes.json().cardTokenId;
    const maskedPan = cardRes.json().maskedPan;

    const fundCard = post(`/v1/vcc/cards/${cardId}/fund`, {
        merchantId, idempotencyKey: 'fund_' + runId, amount: FUND_AMOUNT,
    });
    if (fundCard.status !== 200) {
        throw new Error(`card funding failed ${fundCard.status}: ${fundCard.body}`);
    }

    console.log(`setup: scenario=${SCENARIO} cardId=${cardId} maskedPan=${maskedPan} ` +
        `cardTokenId=${cardTokenId} funded=${FUND_AMOUNT} authAmount=${AUTH_AMOUNT}`);
    return {runId, cardId, cardTokenId, maskedPan, walletId, stormAuthId: 'auth_storm_' + runId};
}

// ── smoke: functional walk of the decision core ──────────────────────────────
export function smoke(data) {
    const {cardId, cardTokenId, runId} = data;
    const amt = AUTH_AMOUNT;

    const before = getCard(cardId);

    // 1. First delivery → approved, hold moves.
    const a1 = 'auth_smoke1_' + runId;
    let res = authorize(cardTokenId, a1, amt);
    check(res, {
        'approve → 200': (r) => r.status === 200,
        'approve → APPROVED': (r) => r.json('decision') === 'APPROVED',
        'approve → no reason': (r) => r.json('reason') == null,
    }) || sysErrors.add(1);

    const afterAuth = getCard(cardId);
    check(afterAuth, {
        'hold moved: balance decreased by amount':
            (c) => near(c.balance, Number(before.balance) - Number(amt)),
        'hold moved: frozen increased by amount':
            (c) => near(c.frozenBalance, Number(before.frozenBalance) + Number(amt)),
    });

    // 2. Duplicate delivery of the SAME authorizationId → replayed, NO second hold.
    //    This is the core fix-#1 assertion.
    res = authorize(cardTokenId, a1, amt);
    check(res, {
        'replay → 200': (r) => r.status === 200,
        'replay → APPROVED (same decision)': (r) => r.json('decision') === 'APPROVED',
    });
    const afterReplay = getCard(cardId);
    check(afterReplay, {
        'replay did NOT move a second hold (balance unchanged)':
            (c) => near(c.balance, afterAuth.balance),
        'replay did NOT move a second hold (frozen unchanged)':
            (c) => near(c.frozenBalance, afterAuth.frozenBalance),
    });

    // 3. Over-balance auth → declined with INSUFFICIENT_FUNDS, nothing moves.
    const a2 = 'auth_smoke2_' + runId;
    const tooMuch = String(Number(FUND_AMOUNT) + 100);
    res = authorize(cardTokenId, a2, tooMuch);
    check(res, {
        'decline → 200': (r) => r.status === 200,
        'decline → DECLINED': (r) => r.json('decision') === 'DECLINED',
        'decline → INSUFFICIENT_FUNDS': (r) => r.json('reason') === 'INSUFFICIENT_FUNDS',
    });

    // 4. Replaying the decline returns the same stored decision.
    res = authorize(cardTokenId, a2, tooMuch);
    check(res, {
        'decline replay → DECLINED': (r) => r.json('decision') === 'DECLINED',
        'decline replay → INSUFFICIENT_FUNDS': (r) => r.json('reason') === 'INSUFFICIENT_FUNDS',
    });
    const afterDeclines = getCard(cardId);
    check(afterDeclines, {
        'declines moved nothing': (c) =>
            near(c.balance, afterAuth.balance) && near(c.frozenBalance, afterAuth.frozenBalance),
    });

    // 5. Unknown card → CARD_NOT_FOUND.
    res = authorize('ctok_unknown_' + runId, 'auth_smoke3_' + runId, amt);
    check(res, {
        'unknown card → DECLINED / CARD_NOT_FOUND': (r) =>
            r.status === 200 && r.json('reason') === 'CARD_NOT_FOUND',
    });

    // 6. Contract: authorizationId is mandatory.
    res = post('/internal/issuer/authorize',
        {cardTokenId, amount: amt, currency: 'USD', stan: '000001'});
    check(res, {'missing authorizationId → 400': (r) => r.status === 400});
}

// ── replay-storm: N concurrent deliveries of ONE authorizationId ─────────────
export function replayStorm(data) {
    const res = authorize(data.cardTokenId, data.stormAuthId, AUTH_AMOUNT);
    const ok = check(res, {
        'storm → 200': (r) => r.status === 200,
        'storm → APPROVED': (r) => r.json('decision') === 'APPROVED',
    });
    sysErrors.add(!ok);
}

// ── auth-rate: distinct authorizations at a constant arrival rate ────────────
export function authRate(data) {
    const authorizationId =
        `auth_${data.runId}_${exec.vu.idInTest}_${exec.scenario.iterationInInstance}`;
    const res = authorize(data.cardTokenId, authorizationId, AUTH_AMOUNT);

    if (res.status !== 200) {
        sysErrors.add(1);
        return;
    }
    sysErrors.add(0);
    const decision = res.json('decision');
    if (decision === 'APPROVED') {
        approvals.add(1);
    } else {
        declines.add(1);   // INSUFFICIENT_FUNDS once the card runs dry — see sizing note
    }
}

// ── teardown: prove ledger-level invariants ──────────────────────────────────
export function teardown(data) {
    const card = getCard(data.cardId);
    console.log(`\nteardown: card balance=${card.balance} frozen=${card.frozenBalance}`);

    if (SCENARIO === 'replay-storm') {
        // The storm delivered STORM_ITERS copies of one authorization.
        // Exactly ONE hold may have moved.
        const oneHold = near(card.frozenBalance, AUTH_AMOUNT)
            && near(card.balance, Number(FUND_AMOUNT) - Number(AUTH_AMOUNT));
        if (!oneHold) {
            fail(`REPLAY-STORM FAILED: expected exactly one hold of ${AUTH_AMOUNT}, ` +
                `got frozen=${card.frozenBalance} balance=${card.balance}`);
        }
        console.log(`replay-storm OK: ${STORM_ITERS} deliveries → exactly one hold of ${AUTH_AMOUNT}`);
    }

    // Conservation: funds only move between card and hold — never appear or vanish.
    if (!near(Number(card.balance) + Number(card.frozenBalance), FUND_AMOUNT)) {
        fail(`CONSERVATION FAILED: balance ${card.balance} + frozen ${card.frozenBalance} ` +
            `!= funded ${FUND_AMOUNT}`);
    }

    // Wallet chain integrity (balance projection, gapless seq, HMAC chain, journals).
    const verify = http.get(`${BASE_URL}/internal/bench/verify/${data.walletId}`,
        {headers: JSON_HEADERS});
    if (verify.status !== 200) {
        fail(`wallet verify failed ${verify.status}: ${verify.body}`);
    }
    const v = verify.json();
    if (!(v.balanceOk && v.balanceSumOk && v.seqOk && v.chainOk && v.journalOk)) {
        fail(`WALLET LEDGER INVARIANTS FAILED: ${verify.body}`);
    }
    console.log('teardown OK: conservation + wallet ledger invariants hold');
}
