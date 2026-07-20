# Phase MR — End-to-End Testing Guide

This guide walks through manual E2E verification of the Phase MR rail stack: ISO 8583 card rail, ISO 20022 bank rail, VCC (prepaid card) product, Kafka settlement → VA ledger journals, and the reconciliation exception endpoint. Run through each flow in order; earlier flows create state that later flows build on.

---

## 1. Prerequisites

### 1.1 Start the stack

```bash
# From repo root — brings up postgres, kafka, rail-service, rail-simulator, virtual-account-service
docker compose --profile rail --profile virtual-account up --build
```

Wait until all five services are healthy (watch `docker compose ps`):

| Service           | Health check               |
|-------------------|---------------------------|
| postgres          | `pg_isready`               |
| kafka             | broker listener ready      |
| rail-service      | `GET :8081/actuator/health` |
| rail-simulator    | `GET :9099/actuator/health` |
| virtual-account   | `GET :8086/actuator/health` |

### 1.2 Port map

| Service           | Local port | Endpoint base               |
|-------------------|------------|------------------------------|
| gateway-service   | 8080       | `http://localhost:8080`      |
| rail-service      | 8081       | `http://localhost:8081`      |
| rail-simulator    | 9099       | `http://localhost:9099`      |
| virtual-account   | 8086       | `http://localhost:8086`      |
| postgres          | 5432       | `psql -p 5432 -U pay_app_user` |

### 1.3 PAN suffix table (ISO 8583 scenarios)

The card-network-sim routes by the **last 4 digits** of the PAN. BIN prefix can be `4` (Visa) or `5` (Mastercard). BIN `999999` routes to the VA issuer.

| Last 4 digits | Scenario               | Expected status |
|---------------|------------------------|-----------------|
| anything else | APPROVE                | `APPROVED`      |
| `0001`        | Insufficient funds     | `DECLINED`      |
| `0002`        | Do not honor           | `DECLINED`      |
| `0003`        | **Timeout** (no reply) | `UNKNOWN`       |
| `0004`        | Late response          | `UNKNOWN` → reversal auto-resolves |
| `0006`        | Invalid card           | `DECLINED`      |
| `0007`        | Issuer unavailable     | `DECLINED`      |

---

## 2. Flow A — ISO 8583 Card Rail (Standard PAN)

Tests MR1 + MR2: Netty TCP channel, jPOS codec, UNKNOWN/reversal discipline.

### A1. Happy path — approved

```bash
curl -s -X POST http://localhost:8081/v1/rail/authorize \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId":     "mer_test_001",
    "idempotencyKey": "idem_card_a1",
    "amount":         50.00,
    "currency":       "USD",
    "testPan":        "4111111111111234",
    "expiry":         "12/26"
  }' | jq .
```

**Expected response:**

```json
{
  "railPaymentId": "rp_<snowflake>",
  "status":        "APPROVED",
  "authCode":      "<6-char>",
  "responseCode":  "00",
  "networkRef":    "<rrn>",
  "failureReason": null
}
```

**Verify in DB:**

```sql
-- psql -p 5432 -U pay_app_user -d msx_rail
SELECT payment_id, status, network, amount, masked_pan, card_token_id
FROM rail_payment
WHERE idempotency_key = 'idem_card_a1';
-- status = APPROVED
```

### A2. Idempotency — same key returns same result

```bash
# Rerun the exact same request
curl -s -X POST http://localhost:8081/v1/rail/authorize \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId":     "mer_test_001",
    "idempotencyKey": "idem_card_a1",
    "amount":         50.00,
    "currency":       "USD",
    "testPan":        "4111111111111234",
    "expiry":         "12/26"
  }' | jq .
```

**Expected:** same `railPaymentId` as A1, same `status: APPROVED`. No new row in `rail_payment`.

### A3. Decline — insufficient funds

```bash
curl -s -X POST http://localhost:8081/v1/rail/authorize \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId":     "mer_test_001",
    "idempotencyKey": "idem_card_a3",
    "amount":         100.00,
    "currency":       "USD",
    "testPan":        "4111111111110001",
    "expiry":         "12/26"
  }' | jq .
```

**Expected:** `"status": "DECLINED"`, `"responseCode": "51"`, `"authCode": null`.

### A4. Timeout → UNKNOWN → reversal

This uses PAN suffix `0003`. The TCP channel times out; rail-service marks the payment `UNKNOWN` and schedules a reversal task.

```bash
curl -s -X POST http://localhost:8081/v1/rail/authorize \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId":     "mer_test_001",
    "idempotencyKey": "idem_card_a4",
    "amount":         75.00,
    "currency":       "USD",
    "testPan":        "4111111111110003",
    "expiry":         "12/26"
  }' | jq .
```

**Expected:** `"status": "UNKNOWN"` (request takes ~30s for the timeout to fire).

**Verify reversal task was created:**

```sql
-- psql -p 5432 -U pay_app_user -d msx_rail
SELECT rt.payment_id, rt.status, rt.attempt_count, rp.status AS payment_status
FROM rail_reversal_task rt
JOIN rail_payment rp ON rp.payment_id = rt.payment_id
WHERE rp.idempotency_key = 'idem_card_a4';
-- rt.status should transition: PENDING → CLAIMED → (RESOLVED or exhausted)
```

**Verify reversal logged:**

```sql
SELECT payment_id, mti, network, created_at
FROM rail_iso8583_log
WHERE payment_id = (
  SELECT payment_id FROM rail_payment WHERE idempotency_key = 'idem_card_a4'
)
ORDER BY created_at;
-- Expect: SEND_0200, REVERSAL_SENT_0400, REVERSAL_CONFIRMED_0410 (or REVERSAL_EXHAUSTED)
```

**Verify reconciliation surface:** after the reversal task runs, the payment should no longer appear in the exception list. If it times out without confirmation, it will appear:

```bash
curl -s http://localhost:8081/v1/rail/reconciliation/exceptions | jq .
```

### A5. Mastercard routing

```bash
curl -s -X POST http://localhost:8081/v1/rail/authorize \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId":     "mer_test_001",
    "idempotencyKey": "idem_card_a5",
    "amount":         30.00,
    "currency":       "USD",
    "testPan":        "5500000000001234",
    "expiry":         "12/26"
  }' | jq .
```

**Verify in DB:** `network = 'MC_SIM'`.

---

## 3. Flow B — VCC (Prepaid Card) Full Lifecycle

Tests MR0 VA ledger + MR1 ISO 8583 issuer path + MR4 settlement journal.

### B1. Create a merchant WALLET account

```bash
curl -s -X POST http://localhost:8086/internal/va/accounts \
  -H "Content-Type: application/json" \
  -H "X-Internal-Token: internal-dev-secret" \
  -d '{
    "merchantId":  "mer_test_001",
    "orgId":       "org_test",
    "ledgerAccountType": "WALLET",
    "asset":       "USD",
    "mode":        "TEST"
  }' | jq .

# Save the returned ledgerAccountId:
WALLET_ACCOUNT_ID="ac_<id from response>"
```

**Expected response:**

```json
{
  "ledgerAccountId":   "ac_<snowflake>",
  "mode":              "TEST",
  "ledgerAccountType": "WALLET",
  "merchantId":        "mer_test_001",
  "asset":             "USD",
  "balance":           0,
  "status":            "ACTIVE"
}
```

You can also check balance at any time:

```bash
curl -s http://localhost:8086/v1/va/accounts/${WALLET_ACCOUNT_ID} | jq .
curl -s "http://localhost:8086/v1/va/accounts?merchantId=mer_test_001" | jq .
```

### B2. Create a virtual card

```bash
WALLET_ACCOUNT_ID="ac_wallet_mer001"

curl -s -X POST http://localhost:8086/v1/vcc/cards \
  -H "Content-Type: application/json" \
  -d "{
    \"merchantId\":    \"mer_test_001\",
    \"ownerAccountId\": \"${WALLET_ACCOUNT_ID}\",
    \"currency\":      \"USD\",
    \"spendingLimit\": 500.00
  }" | jq .
```

**Expected response:**

```json
{
  "cardId":    "card_<snowflake>",
  "cardTokenId": "ctok_<simulator-token>",
  "testPan":   "999999XXXXXX1234",
  "maskedPan": "999999****1234",
  "bin":       "999999",
  "currency":  "USD",
  "expiry":    "2027-06-29"
}
```

**Save `testPan`, `cardId`, and `cardTokenId` — `testPan` is returned only once.**

```bash
CARD_ID="card_<id from response>"
TEST_PAN="999999XXXXXX1234"
```

### B3. Fund the card

Transfers from the WALLET to the PREPAID_CARD ledger account.

```bash
curl -s -X POST "http://localhost:8086/v1/vcc/cards/${CARD_ID}/fund" \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId":     "mer_test_001",
    "idempotencyKey": "fund_vcc_b3",
    "amount":         200.00
  }' | jq .
```

**Verify balances:**

```sql
SELECT ledger_account_id, ledger_account_type, balance
FROM ledger_account
WHERE merchant_id = 'mer_test_001'
ORDER BY ledger_account_type;
```

**Verify ledger entries (double-entry):**

```sql
SELECT e.ledger_account_id, e.direction, e.amount, e.balance_after
FROM va_ledger_entry e
JOIN ledger_account a ON a.ledger_account_id = e.ledger_account_id
WHERE a.merchant_id = 'mer_test_001'
ORDER BY e.created_at;
-- DR WALLET 200
-- CR PREPAID_CARD 200  (liability moves wallet -> card)
```

### B4. Authorize using the VCC — approved

```bash
curl -s -X POST http://localhost:8081/v1/rail/authorize \
  -H "Content-Type: application/json" \
  -d "{
    \"merchantId\":     \"mer_test_001\",
    \"idempotencyKey\": \"idem_vcc_b4\",
    \"amount\":         80.00,
    \"currency\":       \"USD\",
    \"testPan\":        \"${TEST_PAN}\",
    \"expiry\":         \"12/26\"
  }" | jq .
```

The card-network-sim detects BIN `999999`, mints an `authorizationId` for the auth, derives a simulator `cardTokenId` from DE2, and calls `POST :8086/internal/issuer/authorize` with that token.

**Expected response:** `"decision": "APPROVED"`.

**Verify the hold ledger account was funded on authorization:**

```sql
SELECT ledger_account_id, ledger_account_type, balance
FROM ledger_account
WHERE ledger_account_type IN ('PREPAID_CARD', 'PREPAID_CARD_HOLD')
ORDER BY ledger_account_type;
-- PREPAID_CARD:      balance decreased by 80.00
-- PREPAID_CARD_HOLD: balance increased by 80.00
```

**Save the `railPaymentId`:**

```bash
RAIL_PAYMENT_ID="rp_<from response>"
```

### B5. Settlement — Kafka event → VA ledger journal

When the card sale is approved on the ISO 8583 path (MR1), rail-service publishes a `RailSettlementEvent` to Kafka topic `rail.settlement.events`. The VA Kafka consumer posts the ledger journal that moves held funds to the card-network receivable account.

**Wait ~2 seconds for the Kafka consumer to process, then verify:**

```sql
-- Ledger journal for the card sale
-- DR PREPAID_CARD_HOLD / CR CARD_NETWORK_RECEIVABLE (va_rail_visa_rcv) — hold extinguished into network obligation
SELECT e.ledger_account_id, e.direction, e.amount, e.balance_after, e.source_event_id
FROM va_ledger_entry e
JOIN ledger_account a ON a.ledger_account_id = e.ledger_account_id
WHERE a.ledger_account_type IN ('CARD_NETWORK_RECEIVABLE', 'PREPAID_CARD_HOLD')
ORDER BY e.created_at DESC
LIMIT 4;
```

**Verify the hold balance was released to receivable:**

```sql
SELECT ledger_account_id, ledger_account_type, balance
FROM ledger_account
WHERE ledger_account_type IN ('PREPAID_CARD', 'PREPAID_CARD_HOLD', 'CARD_NETWORK_RECEIVABLE')
ORDER BY ledger_account_type;
-- PREPAID_CARD_HOLD: balance decreased by 80.00
-- CARD_NETWORK_RECEIVABLE: balance increased by 80.00
```

### B6. VCC decline — insufficient funds

Fund the card with only 10.00 and try to charge 50.00.

```bash
# Create a new card for this test (or reuse B2 card at 120.00 available)
curl -s -X POST http://localhost:8081/v1/rail/authorize \
  -H "Content-Type: application/json" \
  -d "{
    \"merchantId\":     \"mer_test_001\",
    \"idempotencyKey\": \"idem_vcc_b6\",
    \"amount\":         999.00,
    \"currency\":       \"USD\",
    \"testPan\":        \"${TEST_PAN}\",
    \"expiry\":         \"12/26\"
  }" | jq .
```

**Expected:** `"status": "DECLINED"`, `"responseCode": "51"`.

**Verify hold balance unchanged** (no hold journal on decline).

### B7. List cards — pagination

```bash
curl -s "http://localhost:8086/v1/vcc/cards?merchantId=mer_test_001&page=0&size=20" | jq .
```

**Expected:** `PagedResult` with `content`, `page`, `size`, `totalElements`, `totalPages`.

### B8. Close the card — sweeps balance back

```bash
curl -s -X DELETE "http://localhost:8086/v1/vcc/cards/${CARD_ID}?merchantId=mer_test_001"
# 204 No Content
```

**Verify balance swept back to WALLET:**

```sql
SELECT ledger_account_id, ledger_account_type, balance, status
FROM ledger_account
WHERE merchant_id = 'mer_test_001'
  AND ledger_account_type IN ('WALLET', 'PREPAID_CARD', 'PREPAID_CARD_HOLD');
-- WALLET: increased by remaining PREPAID_CARD balance
-- PREPAID_CARD and PREPAID_CARD_HOLD: status = CLOSED
```

---

## 4. Flow C — ISO 20022 Bank Rail (SEPA_SIM)

Tests MR3: pain.001 → pain.002 → async poller picks up pacs.002 → SETTLED + MR4 settlement journal.

### C1. Create a merchant WALLET account (if not already done in B1)

```bash
# Skip if you already ran Flow B1 — the same account works for bank transfers.
curl -s -X POST http://localhost:8086/internal/va/accounts \
  -H "Content-Type: application/json" \
  -H "X-Internal-Token: internal-dev-secret" \
  -d '{
    "merchantId":  "mer_test_001",
    "orgId":       "org_test",
    "ledgerAccountType": "WALLET",
    "asset":       "USD",
    "mode":        "TEST"
  }' | jq .
```

### C2. Initiate a bank transfer

```bash
curl -s -X POST http://localhost:8081/v1/rail/bank-transfers \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId":     "mer_test_001",
    "idempotencyKey": "idem_bank_c2",
    "amount":         300.00,
    "currency":       "USD",
    "creditorIban":   "DE89370400440532013000",
    "creditorName":   "Creditor Corp",
    "debtorIban":     "GB29NWBK60161331926819",
    "debtorName":     "Debtor Ltd",
    "network":        "SEPA_SIM"
  }' | jq .
```

**Expected immediate response (pain.002 = accepted):**

```json
{
  "railPaymentId": "rp_<snowflake>",
  "status":        "ACCEPTED",
  "responseCode":  "ACCP",
  "networkRef":    "<EndToEndId>",
  "failureReason": null
}
```

**Save the `railPaymentId`:**

```bash
BANK_PAYMENT_ID="rp_<from response>"
```

### C3. Wait for settlement (pacs.002 ACSC)

The bank poller runs every 5 seconds. After ~5–10 seconds, the payment transitions `ACCEPTED → SETTLED`.

```bash
# Poll the DB or watch logs
sleep 10
```

**Verify settlement in rail DB:**

```sql
-- psql -p 5432 -U pay_app_user -d msx_rail
SELECT payment_id, status, network, amount
FROM rail_payment
WHERE idempotency_key = 'idem_bank_c2';
-- status = SETTLED
```

**Verify ISO 20022 message log:**

```sql
SELECT message_type, end_to_end_id, status_code, created_at
FROM rail_iso20022_log
WHERE payment_id = '<BANK_PAYMENT_ID>'
ORDER BY created_at;
-- pain.001 → pain.002 (ACCP) → pacs.002 (ACSC)
```

### C4. Verify ledger journal — bank transfer settled

The Kafka settlement event should trigger a `BANK_CREDIT_TRANSFER` journal in the VA ledger.

```sql
-- DR BANK_RAIL_RECEIVABLE (va_rail_sepa_rcv) / CR merchant WALLET
SELECT e.ledger_account_id, e.direction, e.amount, e.balance_after
FROM va_ledger_entry e
WHERE e.ledger_account_id IN ('va_rail_sepa_rcv', 'ac_wallet_mer001')
ORDER BY e.created_at DESC
LIMIT 4;
```

**Verify WALLET balance increased:**

```sql
SELECT balance FROM ledger_account WHERE ledger_account_id = 'ac_wallet_mer001';
-- 300.00
```

### C5. FedNow_SIM variant

```bash
curl -s -X POST http://localhost:8081/v1/rail/bank-transfers \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId":     "mer_test_001",
    "idempotencyKey": "idem_bank_c5",
    "amount":         150.00,
    "currency":       "USD",
    "creditorIban":   "US123456789012345678",
    "creditorName":   "FedNow Creditor",
    "debtorIban":     "US987654321098765432",
    "debtorName":     "FedNow Debtor",
    "network":        "FEDNOW_SIM"
  }' | jq .
```

**Verify in DB:** `network = 'FEDNOW_SIM'`, eventually `status = SETTLED`.

---

## 5. Flow D — Reconciliation Exception Endpoint

Tests MR4: `GET /v1/rail/reconciliation/exceptions`.

### D1. Baseline — after a timeout payment, exceptions should appear

Run Flow A4 (timeout PAN) and let the reversal task exhaust all retries (or confirm):

```bash
curl -s http://localhost:8081/v1/rail/reconciliation/exceptions | jq .
```

**Expected shape:**

```json
{
  "exceptions": [
    {
      "paymentId":   "rp_<id>",
      "type":        "UNKNOWN",
      "detail":      "Payment stuck in unresolved state",
      "detectedAt":  "2026-06-29T..."
    }
  ],
  "total": 1
}
```

If the reversal succeeded (0410 received and payment moved to `REVERSED`), the exception will clear from the list.

### D2. After clean runs — exceptions list should be empty

After all payments in Flows A, B, C complete cleanly:

```bash
curl -s http://localhost:8081/v1/rail/reconciliation/exceptions | jq '.total'
# Expect: 0
```

---

## 6. Flow E — Idempotency and Duplicate Kafka Event

Tests MR4 idempotency: duplicate settlement event must not create a second ledger journal.

### E1. Confirm no double-posting on re-sent Kafka event

This is automatically enforced by `LedgerFacade.postIfNew()`. Verify by inspecting the VA inbox table after the settlement in Flow B5:

```sql
-- msx_virtual_account DB
SELECT event_id, event_type, processed_at
FROM va_inbox_event
WHERE event_type = 'rail-card-sale'
ORDER BY processed_at DESC
LIMIT 5;
```

The event ID from the `RailSettlementEvent.envelope.eventId` should appear exactly once. If a duplicate were delivered, `postIfNew` would return `false` and no new ledger entry would be created.

---

## 7. Checking the Full Double-Entry Balance Sheet

After completing Flows A–D, verify the VA ledger is balanced:

```sql
-- All DEBIT-normal accounts: sum of balance should equal sum of CREDIT-normal balances
-- (simplified check — real check would be sum of all entries)
SELECT
    a.ledger_account_type,
    a.normal_balance,
    SUM(a.balance) AS total_balance
FROM ledger_account a
WHERE a.mode = 'TEST'
GROUP BY a.ledger_account_type, a.normal_balance
ORDER BY a.ledger_account_type;
```

Every journal the `CardRailSettlementHandler` posts is validated by `NetZeroValidator` — if any call succeeded, the books are balanced by construction.

---

## 8. Internal Auth Verification

The `/internal/issuer/authorize` endpoint requires `X-Internal-Token`. Verify the guard works:

```bash
# Should return 401
curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8086/internal/issuer/authorize \
  -H "Content-Type: application/json" \
  -d '{"authorizationId":"auth_manual_001","cardTokenId":"ctok_from_card_create_response","amount":10.00,"currency":"USD","stan":"000001"}'

# Should return 200 with decision
curl -s -X POST http://localhost:8086/internal/issuer/authorize \
  -H "Content-Type: application/json" \
  -H "X-Internal-Token: internal-dev-secret" \
  -d '{"authorizationId":"auth_manual_001","cardTokenId":"ctok_from_card_create_response","amount":10.00,"currency":"USD","stan":"000001"}' | jq .
```

The endpoint is idempotent on `authorizationId`: repeating the same call replays the
stored decision without posting a second hold. Use a fresh `authorizationId` to make
a new authorization.

---

## 9. Flow F — Gateway-Rail Bridge (MR5-B)

Tests that the payment dashboard can drive real ISO 8583 / ISO 20022 flows via the `SIMULATOR` provider account. Requires **both** gateway-service and rail-service running, with `RAIL_ENABLED=true` set in gateway-service.

### F1. Start gateway with rail bridge enabled

Add to `docker-compose.override.yml` (or set env in the gateway container):

```yaml
environment:
  RAIL_ENABLED: "true"
  RAIL_BASE_URL: "http://rail-service:8081"
  PROVIDER_SIMULATOR_ENABLED: "true"
```

Then restart gateway-service:

```bash
docker compose up -d gateway-service
```

### F2. Confirm payment via dashboard → APPROVED path

Using a merchant API key:

```bash
# 1. Create payment intent
INTENT=$(curl -s -X POST http://localhost:8080/api/v1/payment-intents \
  -H "Authorization: Bearer sk_test_<apikey>" \
  -H "Content-Type: application/json" \
  -d '{"amount": 5000, "currency": "USD", "paymentMethodType": "card"}' | jq -r .id)

# 2. Confirm — paymentMethodId carries the testPan; last-4 = 1234 → APPROVE
curl -s -X POST http://localhost:8080/api/v1/payment-intents/$INTENT/confirm \
  -H "Authorization: Bearer sk_test_<apikey>" \
  -H "Content-Type: application/json" \
  -d '{"paymentMethodId": "4111111111111234", "paymentMethodType": "card"}' | jq .status
```

**Expected:** `"SUCCEEDED"`.

### F3. Confirm payment → DECLINED path (PAN suffix 0001)

```bash
INTENT=$(curl -s -X POST http://localhost:8080/api/v1/payment-intents \
  -H "Authorization: Bearer sk_test_<apikey>" \
  -H "Content-Type: application/json" \
  -d '{"amount": 5000, "currency": "USD"}' | jq -r .id)

curl -s -X POST http://localhost:8080/api/v1/payment-intents/$INTENT/confirm \
  -H "Authorization: Bearer sk_test_<apikey>" \
  -H "Content-Type: application/json" \
  -d '{"paymentMethodId": "4111111111110001"}' | jq .status
```

**Expected:** `"FAILED"`.

### F4. Confirm payment → UNKNOWN path (PAN suffix 0003) — async resolution

```bash
INTENT=$(curl -s -X POST http://localhost:8080/api/v1/payment-intents \
  -H "Authorization: Bearer sk_test_<apikey>" \
  -H "Content-Type: application/json" \
  -d '{"amount": 5000, "currency": "USD"}' | jq -r .id)

# Confirm — ISO 8583 auth will time out; gateway stays PROCESSING
curl -s -X POST http://localhost:8080/api/v1/payment-intents/$INTENT/confirm \
  -H "Authorization: Bearer sk_test_<apikey>" \
  -H "Content-Type: application/json" \
  -d '{"paymentMethodId": "4111111111110003"}' | jq .status
# → "PROCESSING"

# Wait ~30–90s for reversal task to complete, then poll:
sleep 60
curl -s http://localhost:8080/api/v1/payment-intents/$INTENT \
  -H "Authorization: Bearer sk_test_<apikey>" | jq .status
# → "FAILED"
```

**What happens under the hood:**
1. Gateway confirms → `MasonSimulator.sendCharge()` → `RailServiceClient.authorize()` → `POST /v1/rail/authorize`
2. Rail-service sends 0200 to rail-simulator → no reply within timeout → status `UNKNOWN`
3. Rail-service creates reversal task → sends 0400 → 0410 approved → REVERSED
4. `RailPaymentResolvedPublisher` publishes `RailPaymentResolvedEvent(outcome=FAILED)` to `rail.payment.resolved`
5. `RailPaymentResolvedConsumer` (in gateway) transitions intent to FAILED, writes outbox event
6. Merchant receives `payment_intent.failed` webhook

**Verify in Kafka** (optional):

```bash
docker compose exec kafka kafka-console-consumer \
  --bootstrap-server kafka:9092 --topic rail.payment.resolved --from-beginning --max-messages 1
```

---

## 11. Reset Between Runs

To reset the test state without rebuilding containers:

```bash
# Rail DB
docker compose exec postgres psql -U pay_app_user -d msx_rail \
  -c "TRUNCATE rail_payment, rail_reversal_task, rail_iso8583_log, rail_iso20022_log, rail_network_correlation RESTART IDENTITY CASCADE;"

# VA DB — reset only merchant-specific accounts and ledger (keep seed accounts)
docker compose exec postgres psql -U pay_app_user -d msx_virtual_account \
  -c "DELETE FROM ledger_account WHERE ledger_account_role = 'TENANT' AND merchant_id IS NOT NULL;
      TRUNCATE va_inbox_event RESTART IDENTITY CASCADE;"
# va_ledger_entry is partitioned (64 shards); truncate each:
for i in $(seq 0 63); do
  docker compose exec postgres psql -U pay_app_user -d msx_virtual_account \
    -c "TRUNCATE va_ledger_entry_${i};"
done
```

The V7 seed accounts (`va_rail_visa_rcv`, `va_rail_mc_rcv`, etc.) are preserved by `ON CONFLICT DO NOTHING` on each restart and do not need to be re-inserted.
