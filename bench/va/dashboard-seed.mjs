#!/usr/bin/env node

const BASE_URL = process.env.BASE_URL || 'http://localhost:8086';
const INTERNAL_TOKEN = process.env.INTERNAL_TOKEN || 'internal-dev-secret';
const MERCHANT_ID = process.env.MERCHANT_ID || '0228f8c4-20e7-4e7e-a983-26d1d0e80378';
const CASH_ACCOUNT_ID = process.env.CASH_ACCOUNT_ID || '';
const WALLET_ACCOUNT_ID = process.env.WALLET_ACCOUNT_ID || '';
const RUN_ID = process.env.RUN_ID || `dash${Date.now()}`;
const SEED_AUTHS = (process.env.SEED_AUTHS || 'false') === 'true';

const headers = {
  'Content-Type': 'application/json',
  'X-Internal-Token': INTERNAL_TOKEN,
};

async function request(method, path, body) {
  const res = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    body: body == null ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }
  if (!res.ok) {
    throw new Error(`${method} ${path} failed ${res.status}: ${text}`);
  }
  return data;
}

async function resolveAccounts() {
  if (CASH_ACCOUNT_ID && WALLET_ACCOUNT_ID) {
    return { cashId: CASH_ACCOUNT_ID, walletId: WALLET_ACCOUNT_ID };
  }

  const page = await request('GET', `/v1/va/accounts?merchantId=${encodeURIComponent(MERCHANT_ID)}&mode=TEST&page=0&size=100`);
  const accounts = page.content || [];
  const cash = accounts.find((account) => account.ledgerAccountType === 'CASH');
  const wallet = accounts.find((account) => account.ledgerAccountType === 'WALLET');
  if (!cash || !wallet) {
    throw new Error(`Could not resolve CASH/WALLET for merchant ${MERCHANT_ID}; pass CASH_ACCOUNT_ID and WALLET_ACCOUNT_ID explicitly.`);
  }
  return { cashId: cash.ledgerAccountId, walletId: wallet.ledgerAccountId };
}

async function postBench(tenantLedgerAccountId, externalLedgerAccountId, amount) {
  return request('POST', '/internal/bench/post', {
    tenantLedgerAccountId,
    externalLedgerAccountId,
    amount,
  });
}

async function main() {
  console.log(`Seeding VA dashboard data at ${BASE_URL}`);
  console.log(`merchantId=${MERCHANT_ID} runId=${RUN_ID}`);

  const { cashId, walletId } = await resolveAccounts();
  console.log(`cash=${cashId} wallet=${walletId}`);

  await postBench(cashId, walletId, '1200.00');
  await postBench(cashId, walletId, '375.25');
  console.log('posted wallet top-ups: 1200.00, 375.25');

  const card = await request('POST', '/v1/vcc/cards', {
    merchantId: MERCHANT_ID,
    ownerAccountId: walletId,
    currency: 'USD',
    spendingLimit: '500.00',
  });
  console.log(`created card=${card.cardId} token=${card.cardTokenId} masked=${card.maskedPan}`);

  await request('POST', `/v1/vcc/cards/${encodeURIComponent(card.cardId)}/fund`, {
    merchantId: MERCHANT_ID,
    idempotencyKey: `dashboard-seed-fund-${RUN_ID}`,
    amount: '300.00',
  });
  console.log('funded card: 300.00');

  if (SEED_AUTHS) {
    for (const [idx, amount] of ['42.10', '18.75', '64.33'].entries()) {
      const auth = await request('POST', '/internal/issuer/authorize', {
        authorizationId: `da${String(idx + 1).padStart(2, '0')}${RUN_ID.slice(-6)}`,
        cardTokenId: card.cardTokenId,
        amount,
        currency: 'USD',
        stan: `9${String(idx + 1).padStart(5, '0')}`,
        rrn: `ds${RUN_ID.slice(-10)}`,
      });
      console.log(`auth ${idx + 1}: ${amount} -> ${auth.decision}${auth.reason ? ` (${auth.reason})` : ''}`);
    }
  } else {
    console.log('skipped issuer auth holds (set SEED_AUTHS=true when local card_authorization schema is current)');
  }

  const accounts = await request('GET', `/v1/va/accounts?merchantId=${encodeURIComponent(MERCHANT_ID)}&mode=TEST&page=0&size=100`);
  console.log('\nAccounts after seed:');
  for (const account of accounts.content || []) {
    console.log(`${account.ledgerAccountId}\t${account.ledgerAccountType}\t${account.normalBalance}\t${account.balance} ${account.asset}`);
  }
}

main().catch((err) => {
  console.error(err.message);
  if (err.cause) {
    console.error(err.cause.message || err.cause);
  }
  if (String(err.message).includes('/internal/bench/post failed 404')) {
    console.error('\nBench endpoints are disabled. For local-only seeding, restart VA with VA_BENCH_ENABLED=true.');
  }
  process.exit(1);
});
