# MasonXPay Multi-Rail Payment Infrastructure Lab — Phase MR

## Purpose

Phase MR extends MasonXPay beyond PSP/gateway integration into a simulation of the infrastructure layer that acquirer processors, card networks, and bank payment rails operate. It implements a two-sided payment rail — an acquirer-side client and a network-side simulator — for both ISO 8583 (card payments) and ISO 20022 (bank payments), connected to the existing double-entry ledger through Kafka-driven settlement events.

The phase also introduces a Virtual Credit Card (VCC) product: merchants fund a main wallet account and issue funded virtual cards backed by their balance. The `virtual-account-service` acts as the card issuer, making authorization decisions against the ledger and posting settlement journals on completion.

Together, Phase MR demonstrates production engineering patterns that are typically confined to acquiring banks, payment processors, and card issuers:

- ISO 8583 UNKNOWN state and reversal discipline — timeout is not failure; recovery requires a financial reversal message, not a database rollback
- ISO 20022 multi-message correlation chains across pain.001 → pacs.008 → pacs.002 → camt.054
- The operational difference between card reversal (0400) and bank return (pacs.004)
- How clearing and settlement connect to a double-entry ledger through idempotent Kafka events
- VCC issuance: a funded prepaid card product where the issuer's authorization decisions are driven by real ledger balance state

This phase does not connect to real Visa, Mastercard, SWIFT, SEPA, or FedNow production networks. The simulator reproduces the protocol structures and operational edge cases without requiring network membership or confidential implementation specifications.

## Scope Boundary

Phase MR runs alongside the existing gateway (Phases 0–4, H, O, S) without replacing it. Merchants using Stripe, Square, Braintree, and Mollie continue using the existing `gateway-service` path unchanged. The rail service exposes its own API namespace and its own Maven modules.

## Positioning in the Full System

```
Existing path:
  Merchant API / Checkout
    -> gateway-service (PaymentIntentService)
    -> PSP adapters (Stripe, Square, Braintree, Mollie, MasonSimulator)

New Phase MR path (parallel, separate entry points):
  rail-service APIs
    -> CanonicalPaymentCommand
    -> RailRouter
         -> VisaSimIso8583Adapter  (Netty TCP / jPOS codec)
         -> MastercardSimAdapter   (Netty TCP / jPOS codec)
         -> SepaSimAdapter         (HTTP + XML / JAXB)
         -> FedNowSimAdapter       (HTTP + XML / JAXB)
    -> rail-simulator (card-network-sim + bank-rail-sim)
         -> virtual-account-service (issuer decision for VA-issued VCCs)
    -> RailSettlementEvent -> Kafka -> virtual-account-service (ledger)
```

## Architecture

### Module Layout

```
backend/
  common/                  (existing — shared error, ID, tenant)
  contracts/               (existing — add RailSettlementEvent here)
  gateway-service/         (existing — untouched)
  virtual-account-service/ (existing — add PREPAID_CARD ledger account types,
                                       VirtualCard entity,
                                       issuer auth endpoint,
                                       card management API,
                                       extend SettlementEventConsumer)
  rail-service/            (NEW — canonical model, rail adapters,
                                  ISO8583 client, ISO 20022 client,
                                  rail router, APIs)
  rail-simulator/          (NEW — card-network-sim, issuer-sim fallback,
                                  bank-rail-sim)
```

### Service Ports (Docker)

| Service | Port |
|---|---|
| gateway-service | 8080 (existing) |
| virtual-account-service | 8082 |
| rail-service | 8081 |
| rail-simulator HTTP | 9090 |
| rail-simulator ISO8583 TCP | 9091 |

### Communication

- `rail-service` → `rail-simulator`: **Netty TCP** for ISO8583, **HTTP+XML** for ISO 20022
- `rail-simulator` (card-network-sim) → `virtual-account-service`: **HTTP** for VA-issued VCC auth decisions (BIN 999999)
- `rail-service` → Kafka → `virtual-account-service`: settlement events after card/bank rail settles

## Design Decisions (Locked)

| Decision | Choice | Rationale |
|---|---|---|
| ISO8583 TCP transport | Netty | Event-loop model, first-class timeout/idle handlers, pipeline design for late-response detection |
| ISO8583 codec | jPOS (`ISOMsg`, `GenericPackager`) | 25+ years of production edge-case coverage for bitmap, LLVAR, LLLVAR, field types |
| ISO 20022 transport | HTTP + XML bodies | Message construction is the lesson; transport is secondary |
| ISO 20022 binding | JAXB POJOs (simplified, no full XSD) | Fast to build, shows message structure, XSD validation deferred |
| Rail module count | 2 new modules (rail-service + rail-simulator) | Two-sided — you build both the client and the server |
| Rail API namespace | `/v1/rail/...` on rail-service port | Keeps gateway-service untouched |
| Ledger integration | Extend existing VA SettlementEventConsumer | Ledger already built; add rail settlement event type to contracts |
| VCC model | Prepaid (funded from main wallet) | No credit extended — load money, spend money |

## VCC Product Model

### Concept

A merchant holds a main funding wallet. From that wallet they create one or more Virtual Credit Cards, each funded with a specific amount. Each VCC runs on the card rail through the rail-simulator's card-network-sim. The VA service acts as the card issuer for VA-issued VCCs.

Multiple VCCs per merchant are supported. Each VCC is independent.

### Account Structure

```
LedgerAccount { LedgerAccountType.WALLET }          ← main funding account
  |
  └── funds (internal transfer) ──→  LedgerAccount { LedgerAccountType.PREPAID_CARD }
                                          ← available balance for one VirtualCard
                                      LedgerAccount { LedgerAccountType.PREPAID_CARD_HOLD }
                                          ← held authorizations for the same VirtualCard
```

### VirtualCard Entity (virtual-account-service)

```
virtual_card {
  card_id            VARCHAR PK
  masked_pan         VARCHAR          -- last 4 + BIN prefix only, never raw PAN
  bin                VARCHAR          -- 999999 for VA-issued cards
  vcc_account_id     → ledger_account -- the PREPAID_CARD account
  hold_account_id    → ledger_account -- the PREPAID_CARD_HOLD account
  owner_account_id   → ledger_account -- the main WALLET account that funded it
  status             ENUM(ACTIVE, FROZEN, EXPIRED, CLOSED)
  spending_limit     DECIMAL          -- optional cap below loaded balance
  currency           VARCHAR
  expiry             DATE
  created_at         TIMESTAMP
  updated_at         TIMESTAMP
}
```

### Card Lifecycle Journals

**Fund a VCC (internal transfer):**
```
DR  PREPAID_CARD (vcc_account)   $200
CR  WALLET (main_account)        $200
```

**Auth (0100/0110) — move available funds to hold:**
```
DR  PREPAID_CARD_HOLD          $50
CR  PREPAID_CARD (vcc_account) $50
```

**Settlement — card network pays, issuer books the spend:**
```
DR  CARD_NETWORK_RECEIVABLE      $50
CR  PREPAID_CARD_HOLD            $50   ← held balance consumed
```

**Reversal (0400/0410) — release hold:**
```
DR  PREPAID_CARD (vcc_account)   $50
CR  PREPAID_CARD_HOLD            $50
```

**Close VCC — remaining balance sweeps back:**
```
DR  WALLET (main_account)        $150
CR  PREPAID_CARD (vcc_account)   $150
→ vcc_account closed, virtual_card.status = CLOSED
```

## ISO8583 Card Rail Design

### Stack

```
rail-service
  Netty bootstrap (NioEventLoopGroup)
    -> Iso8583FrameDecoder (length-prefix framing)
    -> Iso8583FrameEncoder
    -> IdleStateHandler (timeout detection)
    -> LateResponseHandler
    -> Iso8583ClientHandler
  jPOS ISOMsg / GenericPackager (codec only)
  VisaSimIso8583Adapter / MastercardSimIso8583Adapter

rail-simulator
  Netty server (NioServerSocketChannel, port 9091)
    -> Iso8583FrameDecoder
    -> Iso8583FrameEncoder
    -> CardNetworkSimHandler
  jPOS ISOMsg / GenericPackager
  IssuerSimulator (rule-based for non-VA BINs)
  VA issuer HTTP client (for BIN 999999)
```

### MTIs Implemented

| MTI | Description |
|---|---|
| 0800 / 0810 | Network management — sign-on, echo test |
| 0100 / 0110 | Authorization request / response |
| 0200 / 0210 | Financial sale request / response |
| 0400 / 0410 | Reversal request / response |
| 0420 / 0430 | Reversal advice / response |

### Key Fields (DE references)

| DE | Field | Notes |
|---|---|---|
| DE2 | PAN | Masked before any log write |
| DE3 | Processing code | Auth=000000, Sale=200000, Reversal=400000 |
| DE4 | Amount | Transaction amount |
| DE7 | Transmission date/time | MMDDHHmmss |
| DE11 | STAN | System Trace Audit Number — not globally unique |
| DE12/13 | Local time/date | |
| DE32 | Acquirer ID | |
| DE37 | RRN | Retrieval Reference Number — not globally unique |
| DE38 | Authorization code | Set by issuer on approval |
| DE39 | Response code | 00=approved, 51=insufficient funds, 14=invalid card, etc. |
| DE41 | Terminal ID | |
| DE42 | Merchant ID | |
| DE49 | Currency code | ISO 4217 numeric |
| DE90 | Original data elements | Used in reversals |

### Correlation Key

STAN and RRN alone are not globally unique. The composite correlation key is:
```
{network}:{acquirer_id}:{stan}:{rrn}:{transmission_date}
```
Stored in `rail_network_correlation` table.

### Simulator Behavior (PAN suffix)

| PAN suffix | Behavior |
|---|---|
| 0000 | Approve |
| 0001 | Decline — insufficient funds (DE39=51) |
| 0002 | Decline — do not honor (DE39=05) |
| 0003 | Timeout — no response (triggers UNKNOWN state) |
| 0004 | Late approve — response arrives after timeout window |
| 0005 | Duplicate response |
| 0006 | Decline — invalid card (DE39=14) |
| 0007 | Issuer unavailable (DE39=91) |

### UNKNOWN State and Reversal Discipline

Timeout does not mean FAILED. It means UNKNOWN.

```
0100 sent → no 0110 within timeout window
  rail_payment.status = UNKNOWN
  reversal_task created (status=PENDING)
  0400 reversal sent → await 0410
    on 0410 received: status = REVERSED, task = RESOLVED
    on late 0110 arriving after reversal: log LATE_RESPONSE_AFTER_REVERSAL exception
    on 0410 timeout: reversal_task retried (up to 3 attempts)
```

Card reversal is not a database rollback. It is a new financial message sent to the network.

## ISO 20022 Bank Rail Design

### Message Families Implemented

| Message | Direction | Trigger |
|---|---|---|
| pain.001 | rail-service → bank-rail-sim | Credit transfer initiation |
| pain.002 | bank-rail-sim → rail-service | Payment status report (ACCP/RJCT) |
| pacs.008 | Internal in bank-rail-sim | FI-to-FI credit transfer (simulated clearing) |
| pacs.002 | bank-rail-sim → rail-service | FI payment status (ACSC/RJCT) |
| pacs.004 | bank-rail-sim → rail-service | Payment return |
| camt.054 | bank-rail-sim → rail-service | Account debit/credit notification |

### ID Correlation Chain

Each payment carries three correlated IDs through the message family:
```
MessageId     → identifies the pain.001 document
EndToEndId    → stable across all messages for one payment (set by originator)
InstructionId → set per instruction step
TransactionId → assigned by clearing infrastructure (pacs.008/002)
```
All four stored in `rail_iso20022_log` and `rail_network_correlation`.

### Async Status Flow

Bank payments are asynchronous. A payment follows one of these paths:

```
pain.001 sent
  → pain.002: ACCP (accepted for processing)
    → pacs.002: ACSC (settlement complete)
      → camt.054: account notification
      → status: SETTLED

  → pain.002: RJCT (rejected)
      → status: FAILED

  → pacs.002: ACSP then pacs.004 (return)
      → status: RETURNED
```

### Simulator Behavior (creditor account suffix)

| Account suffix | Behavior |
|---|---|
| 0000 | Accepted and settled |
| 0001 | Rejected immediately (invalid account) |
| 0002 | Accepted then returned (pacs.004) |
| 0003 | Pending for long time (no settlement notification) |
| 0004 | Duplicate status report |
| 0005 | Settlement notification delayed |
| 0006 | Amount mismatch in camt.054 (reconciliation exception) |

### Return vs Reversal

Bank return (pacs.004) is not the same as a card reversal (0400). A return is a new credit transfer in the opposite direction initiated by the receiving bank. It does not cancel the original pacs.008 — it creates a new transaction that must be reconciled against the original.

## Ledger Account Model Changes

### New LedgerAccountType Values

```java
// Add to LedgerAccountType enum in virtual-account-service

// VCC product
PREPAID_CARD,              // ring-fenced wallet bound to a VirtualCard lifecycle
PREPAID_CARD_HOLD,         // authorized-but-unsettled held funds
                           // NormalBalance.DEBIT, LedgerAccountRole.TENANT

// Rail in-flight tracking (platform books)
CARD_NETWORK_RECEIVABLE,   // amounts owed by card network between sale and settlement
                           // NormalBalance.DEBIT, LedgerAccountRole.EXTERNAL (providerId = network name)

BANK_RAIL_RECEIVABLE,      // amounts owed from bank rail between pain.001 and pacs.002 ACSC
                           // NormalBalance.DEBIT, LedgerAccountRole.EXTERNAL (providerId = rail name)

SUSPENSE_UNKNOWN_TXN,      // card transactions timed out — outcome unknown, reversal pending
                           // NormalBalance.DEBIT, LedgerAccountRole.PLATFORM
```

### Rail Settlement Event (contracts module)

```java
// Add to contracts module
public record RailSettlementEvent(
    String eventId,
    String railPaymentId,
    PaymentRail rail,            // CARD_ISO8583 | BANK_ISO20022
    MoneyMovementType type,      // CARD_SALE | BANK_CREDIT_TRANSFER | CARD_REVERSAL | BANK_RETURN
    String asset,
    BigDecimal amount,
    String vccAccountId,         // non-null for VCC card payments
    String receivableAccountId,  // CARD_NETWORK_RECEIVABLE or BANK_RAIL_RECEIVABLE account
    String networkName,          // VISA_SIM | MC_SIM | SEPA_SIM | FEDNOW_SIM
    Instant settledAt
)
```

## Database Tables (rail-service)

```
rail_payment              — canonical payment record (rail, type, status, amount, currency)
rail_routing_decision     — which adapter was chosen and why (persisted before execution)
rail_iso8583_log          — ISO8583 message log (masked DE2, MTI, STAN, RRN, response code)
rail_iso20022_log         — ISO 20022 message log (message name, IDs, status code, masked XML)
rail_network_correlation  — composite correlation key linking internal ID to network IDs
rail_reversal_task        — pending reversals for UNKNOWN state card transactions
rail_bank_return_task     — pending return tracking for bank rail
```

## Milestone Tracker

### MR0 — Foundation

**Goal:** New modules compile, Docker Compose wires all services, VA service has new account types and VirtualCard skeleton, canonical model defined.

Deliverables:
- Maven modules `rail-service` and `rail-simulator` added to `backend/pom.xml`
- `CanonicalPaymentCommand`, `PaymentRail`, `MoneyMovementType`, `RailResponse`, `PaymentRailAdapter` interface
- `RailRouter` (stub — reads rail from command, logs routing decision)
- Flyway migrations: `rail_payment`, `rail_routing_decision`, `rail_iso8583_log`, `rail_iso20022_log`, `rail_network_correlation`, `rail_reversal_task`, `rail_bank_return_task`
- `LedgerAccountType` enum extended: `PREPAID_CARD`, `PREPAID_CARD_HOLD`, `CARD_NETWORK_RECEIVABLE`, `BANK_RAIL_RECEIVABLE`, `SUSPENSE_UNKNOWN_TXN`
- `VirtualCard` entity + `VirtualCardRepository` (skeleton) in `virtual-account-service`
- `RailSettlementEvent` record added to `contracts` module
- Docker Compose: `rail-service` (8081), `rail-simulator` (9090 HTTP + 9091 TCP)
- All modules compile, health checks pass

Acceptance criteria:
- `cd backend && mvn compile` succeeds across all 6 modules
- `docker compose up --build` starts all services including rail-service and rail-simulator
- Existing gateway-service tests still pass

Status: [x]

---

### MR1 — ISO8583 Card Rail + VCC Issuer

**Goal:** Full ISO8583 auth flow working end-to-end through all three parties: rail-service (acquirer) → rail-simulator (card network) → virtual-account-service (issuer for BIN 999999) and rule-based issuer for standard test PANs.

Deliverables:
- **rail-service**: Netty bootstrap, `Iso8583FrameDecoder/Encoder`, `IdleStateHandler`, `Iso8583ClientHandler`, jPOS `GenericPackager` XML config, `VisaSimIso8583Adapter`, `MastercardSimIso8583Adapter`
- **rail-service**: `POST /v1/rail/authorize` — accepts card auth request, routes to correct adapter, returns canonical response
- **rail-simulator**: Netty TCP server (port 9091), `CardNetworkSimHandler`, BIN-based routing (4xxx → Visa logic, 5xxx → MC logic, 999999 → VA issuer), PAN-suffix behavior table
- **rail-simulator**: VA issuer HTTP client — calls `virtual-account-service` `/internal/issuer/authorize` for BIN 999999
- **virtual-account-service**: `VirtualCard` management API (create, fund, get, list per merchant), card management controller
- **virtual-account-service**: `/internal/issuer/authorize` endpoint — looks up VirtualCard, checks PREPAID_CARD available balance, posts hold journal to PREPAID_CARD_HOLD, returns approve/decline
- Masked ISO8583 log on every send/receive (DE2 masked)
- Unit tests: packager round-trip, adapter field mapping, BIN routing
- Integration test: full auth flow via Netty against local simulator

Acceptance criteria:
- PAN starting with 4 routes to VisaSim adapter
- PAN starting with 5 routes to MastercardSim adapter
- PAN starting with 999999 routes to VA issuer via card-network-sim
- PAN suffix 0000 → approved
- PAN suffix 0001 → declined insufficient funds
- VA VCC with sufficient balance → approved, hold ledger account updated
- VA VCC with insufficient balance → declined
- DE2 never appears unmasked in logs

Status: [x]

---

### MR2 — Timeout, UNKNOWN, and Reversal Discipline

**Goal:** The hardest ISO8583 operational problem: a transaction whose outcome is unknown because the network timed out. Demonstrate that timeout ≠ failed, and that recovery requires a reversal message, not a database rollback.

Deliverables:
- **rail-service**: `IdleStateHandler` drives `UNKNOWN` state on read timeout (not `FAILED`)
- **rail-service**: `ReversalTaskService` — creates `rail_reversal_task` immediately on UNKNOWN, schedules 0400 send
- **rail-service**: `LateResponseHandler` Netty pipeline stage — detects 0110 arriving after reversal was sent, logs `LATE_RESPONSE_AFTER_REVERSAL` reconciliation exception
- **rail-service**: Duplicate response detection — second 0110 for same correlation key is logged and ignored
- **rail-service**: Reversal retry (up to 3 attempts with backoff) if 0410 not received
- **rail-simulator**: PAN suffix 0003 — sends no response (simulates timeout)
- **rail-simulator**: PAN suffix 0004 — sends 0110 after a delay that exceeds rail-service timeout window
- **rail-simulator**: PAN suffix 0005 — sends 0110 twice
- Tests: timeout triggers UNKNOWN + reversal task; late 0110 is logged as exception; reversal retry fires on 0410 timeout

Acceptance criteria:
- PAN suffix 0003 → rail_payment.status = UNKNOWN, rail_reversal_task created
- 0400 sent → 0410 received → status = REVERSED
- PAN suffix 0004 → late 0110 logged, reconciliation exception created
- PAN suffix 0005 → second 0110 silently ignored
- No duplicate ledger entries under any scenario

Status: [x]

---

### MR3 — ISO 20022 Bank Rail

**Goal:** Full ISO 20022 payment message family — pain.001 through camt.054 — across an async status lifecycle. Demonstrates how a bank payment differs from a card auth: multiple message types, asynchronous settlement, return vs reversal.

Deliverables:
- **rail-service**: JAXB POJOs for pain.001, pain.002, pacs.008, pacs.002, pacs.004, camt.054
- **rail-service**: `SepaSimAdapter`, `FedNowSimAdapter` — build pain.001, parse pain.002/pacs.002/pacs.004/camt.054
- **rail-service**: `POST /v1/rail/bank-transfers` — accepts bank transfer request, routes to adapter, returns canonical response
- **rail-service**: Async status polling — poll simulator for payment status updates; or receive async callback on dedicated endpoint
- **rail-simulator**: HTTP server (port 9090), bank-rail-sim: accepts pain.001, generates pain.002, simulates clearing (pacs.008/002), generates camt.054
- **rail-simulator**: Account-suffix-based behavior table
- ISO 20022 correlation ID chain stored in `rail_iso20022_log` and `rail_network_correlation`
- Tests: happy-path settle, reject flow, accept-then-return flow

Acceptance criteria:
- Account suffix 0000 → pain.001 sent, pain.002 ACCP, pacs.002 ACSC, camt.054 received, status = SETTLED
- Account suffix 0001 → pain.002 RJCT, status = FAILED
- Account suffix 0002 → pacs.002 then pacs.004 return, status = RETURNED
- All four IDs (MessageId, EndToEndId, InstructionId, TransactionId) present in correlation table
- Return is recorded as a new rail_payment entry linked to original, not a cancellation

Status: [x]

---

### MR4 — Ledger Integration and Reconciliation

**Goal:** Connect rail settlement outcomes to the existing VA double-entry ledger via Kafka. Detect reconciliation exceptions by comparing the rail message log against ledger state.

Deliverables:
- **rail-service**: Publishes `RailSettlementEvent` to Kafka on card sale settlement and bank transfer settlement
- **virtual-account-service**: `SettlementEventConsumer` extended to handle `RailSettlementEvent`
  - Card sale: DR CARD_NETWORK_RECEIVABLE / CR PREPAID_CARD, release freeze
  - Bank settle: DR BANK_RAIL_RECEIVABLE / CR target account
  - Bank return: reverse the settlement journal
  - SUSPENSE_UNKNOWN_TXN cleared on confirmed reversal
- Seed accounts created at startup: `visa-sim-settlement` (CARD_NETWORK_RECEIVABLE), `mc-sim-settlement` (CARD_NETWORK_RECEIVABLE), `sepa-sim-settlement` (BANK_RAIL_RECEIVABLE), `platform-card-unknown` (SUSPENSE_UNKNOWN_TXN)
- **rail-service**: Reconciliation query — compare `rail_iso8583_log` + `rail_iso20022_log` against ledger entries for the same payment ID, surface exceptions
- `GET /v1/rail/reconciliation/exceptions` — returns unmatched, amount-mismatch, and late-response exceptions
- Tests: settlement event posts correct journal; duplicate event does not duplicate journal; UNKNOWN clears on reversal

Acceptance criteria:
- Card sale settlement publishes event → ledger journal created, net-zero validated
- Bank transfer settle → ledger journal created
- Bank return → reverse journal created
- Duplicate settlement event → idempotent, no second journal
- `GET /v1/rail/reconciliation/exceptions` returns LATE_RESPONSE_AFTER_REVERSAL from MR2 scenario
- All journals balance (NetZeroValidator passes)

Status: [x]

---

### MR5 — VA Account APIs + Gateway-Rail Bridge

**Goal:** Surface the VA ledger through a proper API (removing the raw-SQL prerequisite for E2E testing), then wire the existing `MasonSimulatorPaymentProviderService` in gateway-service to call rail-service instead of returning in-process canned responses. This allows the payment dashboard to drive real ISO 8583 / ISO 20022 flows end-to-end without any curl scripting.

#### MR5-A — VA Account Management APIs

**Deliverables — virtual-account-service:**
- `POST /internal/va/accounts` — admin token required (`X-Internal-Token`). Creates a TENANT ledger account (WALLET, CASH, CREDIT_LINE, etc.) for a merchant. Derives `ledgerAccountRole=TENANT`, `balance=0`, `status=ACTIVE`. Derives `normalBalance` and `assetClass` from `ledgerAccountType`.
- `GET /v1/va/accounts/{ledgerAccountId}` — merchant-facing. Returns `ledgerAccountId`, `ledgerAccountType`, `mode`, `asset`, `balance`, `status`.
- `GET /v1/va/accounts?merchantId=...&page=0&size=20` — merchant-facing, paginated list of all TENANT accounts for a merchant.
- Add `findByMerchantId(merchantId, page, size)` + `countByMerchantId(merchantId)` to `LedgerAccountRepository`.

Security: `/internal/**` already requires `ROLE_INTERNAL` via `InternalTokenFilter`. No new security config changes.

#### MR5-B — Gateway → Rail Bridge

**Deliverables — contracts module:**
- `RailPaymentResolvedEvent(envelope, railPaymentId, outcome)` — published by rail-service when an UNKNOWN payment resolves. `outcome` values: `"SUCCEEDED"` | `"FAILED"`.

**Deliverables — rail-service:**
- `RailSettlementEventPublisher.publishPaymentResolved()` — called by `ReversalTaskService` when reversal confirmed (`UNKNOWN → REVERSED`; outcome=`FAILED`).
- New Kafka topic: `rail.payment.resolved` (config key `rail.kafka.resolved-topic`).

**Deliverables — gateway-service:**
- `ChargeResult` — add `boolean pendingAsyncResolution` field. When `true`, `PaymentIntentService` leaves the intent in `PROCESSING` and saves `providerPaymentId = railPaymentId`.
- `RailServiceClient` — `RestTemplate` wrapper for `POST /v1/rail/authorize`. Config: `app.rail.base-url`.
- `MasonSimulatorPaymentProviderService.sendCharge()` — replaces canned response with `RailServiceClient.authorize()`. Status mapping: `APPROVED → success=true`, `DECLINED → success=false`, `UNKNOWN → pendingAsyncResolution=true`.
- `RailPaymentResolvedConsumer` — Kafka listener on `rail.payment.resolved`. Looks up `PaymentIntent` by `providerPaymentId = railPaymentId` where `status = PROCESSING`; transitions to `FAILED` or `SUCCEEDED`; writes outbox entry.
- `PaymentIntentService.confirmCharge()` — handle `pendingAsyncResolution=true`: skip SUCCEEDED/FAILED transition, save `providerPaymentId`, leave in `PROCESSING`.

**Status flow in gateway after bridge:**
```
Dashboard confirms payment
  → MasonSimulator → rail-service
      ← APPROVED   → gateway: PROCESSING → SUCCEEDED
      ← DECLINED   → gateway: PROCESSING → FAILED
      ← UNKNOWN    → gateway: stays PROCESSING
                       ~30s later: reversal resolves
                       ← RailPaymentResolvedEvent (Kafka)
                       → gateway: PROCESSING → FAILED
```

Acceptance criteria:
- `POST /internal/va/accounts` creates a WALLET account; GET returns correct balance
- Payment dashboard charge via SIMULATOR provider goes through real ISO 8583 path
- APPROVED payment → dashboard shows SUCCEEDED
- DECLINED payment (PAN suffix 0001) → dashboard shows FAILED
- Timeout payment (PAN suffix 0003) → dashboard shows PROCESSING, transitions to FAILED after reversal resolves
- BIN 999999 VCC charge routes through VA issuer; balance check fires; DECLINED on insufficient funds

Status: [x]

---

## Engineering Rules (rail-specific)

- Timeout is UNKNOWN, not FAILED. Never transition directly from SUBMITTED_TO_RAIL to FAILED on a TCP timeout.
- Card reversal is not a database rollback. It is a new financial message (0400/0420).
- Bank return is not card reversal. A pacs.004 is a new credit transfer, not a cancellation.
- STAN and RRN are not globally unique. Always use the composite correlation key.
- ISO 20022 EndToEndId must be stable across the entire message family for one payment.
- Never log raw DE2 (PAN), full IBAN, or account numbers. Mask before any persistence.
- Ledger journals must be idempotent. Use the railPaymentId + eventType as the idempotency key.
- Reversal must be attempted for every UNKNOWN card transaction. Do not leave UNKNOWN state unresolved.

## Relationship to Existing Phases

- Phase R (Financial Reconciliation) is absorbed into MR4 for rail-level reconciliation. PSP-level reconciliation (Stripe/Square settlement files) remains deferred in Phase 15.
- Phase N (Direct Card Network Connectivity) is the future real-network successor to Phase MR. MR builds the engineering knowledge; N would add real acquiring membership, HSM, and PCI DSS Level 1 scope.
- `virtual-account-service` ledger is shared between the VA product track and Phase MR. The `SettlementEventConsumer` handles both.
