# Gateway to Virtual Account Event Integration

## Purpose

`virtual-account-service` is optional infrastructure. Gateway registration must work when VA is not running, while VA still needs a reliable way to create merchant ledger resources when it is enabled later.

The integration uses the existing gateway transactional outbox and Kafka lifecycle topic:

```text
gateway registration
  -> outbox_events row in gateway DB
  -> Kafka topic payment.lifecycle.events
  -> VA gateway lifecycle consumer
  -> tenant CASH/WALLET ledger account provisioning
```

This keeps `gateway-service` as the merchant identity and authentication owner, and keeps `virtual-account-service` as the ledger owner. There is no synchronous gateway-to-VA call during merchant registration.

## Current Status

Status: implemented on `feature/gateway-va-merchant-events`.

Runtime smoke result:

- Gateway emitted `merchant.created`.
- Gateway Kafka outbox marked the event published.
- VA consumed the event and inserted `va_inbox_event`.
- VA created default tenant ledger accounts:
  - `CASH`, `DEBIT` normal balance
  - `WALLET`, `CREDIT` normal balance

## Ownership Boundary

Gateway owns:

- user registration and login
- organization and merchant identity
- dashboard session and merchant authorization
- transactional outbox write for merchant lifecycle facts

VA owns:

- ledger accounts
- ledger normal-balance semantics
- inbox idempotency
- account provisioning policy
- future VA dashboard read APIs and operational APIs

The shared contract in `contracts` is a business fact, not a VA command. Gateway publishes "a merchant was created"; VA decides which ledger accounts to create.

## Event Contract

`MerchantCreatedEvent`

```text
type: merchant.created
schema version: 1
payload:
  envelope
  organizationId
  merchantId
  merchantExternalId
  merchantName
  modes
  defaultAsset
```

The event is stored in gateway `outbox_events.payload`. The existing gateway Kafka outbox publisher wraps it into the lifecycle envelope:

```json
{
  "outboxEventId": "...",
  "merchantId": "...",
  "eventType": "merchant.created",
  "resourceId": "...",
  "createdAt": "...",
  "payload": {
    "envelope": {
      "eventId": "...",
      "eventType": "merchant.created",
      "schemaVersion": 1
    },
    "organizationId": "...",
    "merchantId": "...",
    "merchantExternalId": "...",
    "merchantName": "...",
    "modes": ["TEST"],
    "defaultAsset": "USD"
  }
}
```

VA consumes the outer lifecycle message as plain JSON text and maps only `payload` into `MerchantCreatedEvent`.

## Idempotency Model

The integration has three layers of replay protection:

1. Gateway registration writes the outbox event in the same DB transaction as the merchant creation.
2. VA records `event.envelope.eventId` in `va_inbox_event` before provisioning.
3. VA has a DB unique guard for default tenant accounts:

```text
UNIQUE(merchant_id, mode, asset, ledger_account_type)
WHERE ledger_account_role = 'TENANT'
  AND ledger_account_type IN ('CASH', 'WALLET')
```

This makes Kafka redelivery and manual backfill safe. Duplicate events should skip through inbox. Concurrent backfill should converge on one `CASH` and one `WALLET` account per merchant/mode/asset.

## Optional Service Behavior

If VA is down:

- gateway registration still succeeds
- gateway outbox keeps the event unpublished or published to Kafka depending on which component is down
- when VA starts, it consumes from `payment.lifecycle.events` with its own consumer group and provisions accounts

If Kafka is down:

- gateway registration still succeeds
- the gateway outbox publisher records publish failures and retries
- VA eventually receives the event after Kafka recovers

## Backfill

VA also exposes an internal manual provisioning endpoint:

```text
POST /internal/va/merchant-provisioning
```

This is for local/dev repair and future operational backfill. It must remain internal-token guarded. The endpoint is idempotent because provisioning checks existing accounts and the DB unique index protects concurrent calls.

## Local Verification

Start the stack with gateway Kafka outbox enabled:

```bash
KAFKA_OUTBOX_ENABLED=true docker compose --profile virtual-account up --build
```

After registering a merchant, verify gateway outbox:

```bash
docker compose exec -T postgres psql -U pay_app_user -d maxon_x_pay -c "
select id, merchant_id, event_type, kafka_published_at,
       (payload::jsonb #>> '{merchantId}') as payload_merchant_id
from outbox_events
where event_type = 'merchant.created'
order by created_at desc
limit 5;
"
```

Verify VA inbox:

```bash
docker compose exec -T postgres psql -U pay_app_user -d msx_virtual_account -c "
select event_id, event_type, received_at
from va_inbox_event
where event_type = 'merchant.created'
order by received_at desc
limit 5;
"
```

Verify VA tenant accounts:

```bash
docker compose exec -T postgres psql -U pay_app_user -d msx_virtual_account -c "
select ledger_account_id, org_id, merchant_id, mode, ledger_account_type,
       asset, normal_balance, balance, status
from ledger_account
where merchant_id = '<merchant-id>'
order by ledger_account_type;
"
```

Expected default accounts:

```text
CASH    TEST  USD  DEBIT   ACTIVE
WALLET  TEST  USD  CREDIT  ACTIVE
```

Note: the local gateway DB name is currently `maxon_x_pay` in the default Docker volume. If a fresh environment uses a different `DB_NAME`, query that database instead.

## Dashboard Readiness

The dashboard should not call VA directly from the browser. The recommended path is:

```text
dashboard
  -> gateway authenticated API
  -> gateway internal VA client
  -> virtual-account-service internal APIs
```

This preserves gateway ownership of dashboard auth, merchant membership, mode scope, and user session state.

Initial dashboard scope should be read-only:

- VA overview: account balances by mode and asset
- accounts list: tenant `CASH`, `WALLET`, `MERCHANT_RECEIVABLE`, card accounts
- account detail: effective-date statement
- settlement exceptions: open exception worklist

Actions should come after read-only views are stable:

- retry/discard settlement exceptions
- manual merchant VA provisioning/backfill
- future cash-to-wallet transfer
- VCC funding and close operations

## Dashboard Offline State

Because VA is optional, gateway proxy endpoints should degrade cleanly when VA is unavailable:

- return a controlled "VA unavailable/not enabled" response
- dashboard renders an empty/offline state
- other dashboard sections remain usable

Do not make the merchant dashboard globally dependent on VA service health.

## Open Follow-ups

- Add gateway proxy endpoints for VA read APIs.
- Add dashboard read-only VA overview and account list.
- Add end-to-end test for "gateway starts first, VA starts later".
- Decide whether merchant creation should eventually emit both `TEST` and `LIVE` modes when LIVE onboarding is enabled.
