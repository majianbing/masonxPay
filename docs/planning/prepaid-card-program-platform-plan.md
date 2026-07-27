# Prepaid Card Program Platform Plan

## Current State

MasonXPay already has a ledger-backed virtual card foundation inside `virtual-account-service`:

- `VirtualCard` links a card token to a `PREPAID_CARD` ledger account, a paired `PREPAID_CARD_HOLD` account, and the funding wallet.
- `VirtualCardService` supports card creation, card funding, listing, read, and close-with-sweep.
- `CardAuthorizationService` makes idempotent real-time authorization decisions and posts authorization holds atomically.
- `CardRailSettlementHandler` posts card sale and reversal settlement journals from rail events, and parks unsupported movement types for operations retry.
- `rail-simulator` can route BIN `999999` authorization traffic into `virtual-account-service` for balance-backed test decisions.

This is a funded prepaid-card foundation, not a true credit-card issuer product. MasonXPay should model itself as a card-program platform or program manager connected to an issuer bank / issuer processor. The licensed issuer owns regulated issuing obligations. MasonXPay owns program configuration, card controls, ledger accounting, merchant-facing APIs, issuer adapter integration, event processing, and reconciliation.

## Goals

- Build a sponsor-bank-compatible prepaid virtual card platform on top of the existing double-entry ledger.
- Keep issuer integration behind an adapter boundary similar to gateway PSP connectors.
- Use `rail-service` / `rail-simulator` as the first issuer-processor mock so future issuer partners can plug into the same contracts.
- Keep raw PAN, CVV, track data, and card secrets outside MasonXPay core services. Full PAN, if required for an issuing workflow, must live only in a separately isolated PCI card-data vault or issuer adapter boundary.
- Make every money movement idempotent and represented by double-entry ledger postings.
- Separate card lifecycle state, authorization state, clearing state, settlement state, and ledger truth.

## Non-Goals

- No true credit product in the first pass: no underwriting, credit limits, repayment schedules, interest, delinquency, charge-off, or credit bureau reporting.
- No direct card-network issuing license or direct scheme membership.
- No PAN/CVV storage or raw card data handling inside `virtual-account-service`.
- No CVV storage anywhere in MasonXPay. A future issuing-support component may collect CVV only for an immediate authorized workflow and must discard it after use.
- No full-PAN storage in core product, ledger, dashboard, Kafka, logs, or analytics tables. Full PAN storage is allowed only in a dedicated PCI-scoped vault/service if the product requires reveal, embossing, wallet provisioning, or processor API calls.
- No issuer-specific logic embedded directly in card lifecycle services.
- No settlement-file production reconciliation with a real issuer until an issuer partner exists.

## Card Data and PCI Boundary

Core services should store only non-sensitive or minimized card references:

- `cardTokenId` / network or processor token
- `maskedPan`
- `bin`
- `last4`
- `expiryMonth`
- `expiryYear`
- card status and lifecycle metadata
- issuer partner external IDs

Full PAN is cardholder data and expands PCI DSS scope. If MasonXPay needs full PAN for card reveal, customer support, digital wallet provisioning, physical-card embossing, issuer processor calls, or card reissue workflows, it must be stored behind a dedicated PCI-scoped card-data boundary. That boundary should expose narrow purpose-built operations rather than letting product services read PAN directly.

The card-data boundary must provide:

- strong encryption and key isolation
- purpose-scoped access APIs
- short-lived reveal sessions for full PAN display
- complete audit logs for every access
- masking by default in every response
- no raw PAN in application logs, event payloads, metrics, traces, or warehouse exports
- no CVV persistence

`virtual-account-service`, `gateway-service`, `rail-service`, `dashboard`, Kafka topics, and RAG/AI systems must continue to operate without raw PAN or CVV.

## Domain Model Direction

### Card Program

`CardProgram` is the product/configuration container for issued cards.

Expected fields:

- `programId`
- `merchantId`
- `mode`
- `issuerPartnerId`
- `name`
- `currency`
- `binRange` or token range metadata
- `status`: `DRAFT`, `ACTIVE`, `SUSPENDED`, `CLOSED`
- feature flags: virtual cards, physical cards, single-use, wallet tokenization, ATM, cross-border
- default controls: MCC/country/merchant rules, velocity limits, per-transaction limits
- `systemOfRecord`: `INTERNAL`, `EXTERNAL`
- `fundingModel`: `PREFUNDED_CARD_BALANCE`, `PROGRAM_BALANCE`, `JIT_FUNDING`, `EXTERNAL_ISSUER_BALANCE`, `SIMULATED`
- settlement model metadata: prefunded, daily settlement, processor-funded, JIT-funded, externally held, or simulated
- fee schedule reference

### Issuer Partner

`IssuerPartner` represents an issuer bank or issuer processor adapter.

Expected fields:

- `issuerPartnerId`
- `mode`
- `name`
- `adapterType`: `RAIL_SIM`, future `MARQETA`, `LITHIC`, `ADYEN_ISSUING`, `STRIPE_ISSUING`, etc.
- `status`
- encrypted credentials/config pointers
- webhook secret or event verification config
- external program / funding-source identifiers where the partner exposes them

### Cardholder

`Cardholder` represents the person, business, employee, or platform user allowed to hold cards.

Expected fields:

- `cardholderId`
- `merchantId`
- `mode`
- `type`: `INDIVIDUAL`, `BUSINESS`, `EMPLOYEE`, `SERVICE_ACCOUNT`
- `externalIssuerCardholderId`
- `kycStatus`: `PENDING`, `ACTIVE`, `REJECTED`, `SUSPENDED`, `CLOSED`
- minimal display profile, with PII kept out or tokenized where possible

### Virtual Card

Extend the existing `VirtualCard` shape rather than replacing it.

Expected additions:

- `programId`
- `issuerPartnerId`
- `cardholderId`
- `externalIssuerCardId`
- `externalCardToken`
- `externalIssuerAccountId` or `externalCardholderAccountId` when the issuer processor has its own account object
- `cardType`: `VIRTUAL`, `PHYSICAL`
- `status`: expand to `CREATED`, `ACTIVE`, `LOCKED`, `SUSPENDED`, `CLOSING`, `CLOSED`, `TERMINATED`, `EXPIRED`
- `lockReason`, `closedReason`
- `singleUse`
- control profile reference or embedded controls snapshot

### Issuer-Side Accounts and Funding Models

Issuer banks and issuer processors usually expose their own account or funding concepts, but the shape varies by partner:

- Some processors maintain cardholder accounts or general-purpose accounts with per-card or per-user balances.
- Some processors maintain a program funding account, and cards draw from that pool.
- Some processors support just-in-time funding, where the processor sends MasonXPay an authorization/funding request and the card carries no standing processor-side balance.
- Some sponsor-bank programs treat the issuer/processor ledger as the legal system of record and send MasonXPay events to mirror and reconcile.

MasonXPay should therefore model processor-side account IDs as external references, not as ledger truth by default. The local double-entry ledger remains the MasonXPay financial ledger, but its authority depends on the program:

- `systemOfRecord = INTERNAL`: MasonXPay's ledger authorizes, holds, posts, and reconciles against settlement cash. This fits the simulator and any future model where MasonXPay is the approved issuing system of record.
- `systemOfRecord = EXTERNAL`: the issuer processor is authoritative. MasonXPay records mirrored events, applies controls where allowed, and reconciles differences in favor of the issuer processor's official records.

The current `PREPAID_CARD` plus `PREPAID_CARD_HOLD` account pair is one supported implementation for `PREFUNDED_CARD_BALANCE`; it must not become the only possible account model. Program-level funding and JIT funding need different account mappings while still producing deterministic, balanced ledger postings.

## Issuer Adapter Boundary

Create an issuer-side adapter abstraction similar to gateway PSP providers.

Suggested interface:

```java
public interface IssuerCardProviderService {
    IssuerPartnerType provider();
    CreateIssuerCardResult createCard(CreateIssuerCardCommand command, IssuerCredentials credentials);
    IssuerCardResult lockCard(String externalCardId, String reason, IssuerCredentials credentials);
    IssuerCardResult unlockCard(String externalCardId, IssuerCredentials credentials);
    IssuerCardResult terminateCard(String externalCardId, String reason, IssuerCredentials credentials);
    Optional<IssuerCardStatus> fetchCardStatus(String externalCardId, IssuerCredentials credentials);
}
```

For the first implementation, `RAIL_SIM` can map local card creation to simulator token/PAN behavior. Real issuer adapters can later implement the same contract without changing card lifecycle service boundaries.

Authorization, clearing, settlement, and webhook ingestion should be separate inbound contracts because many issuers deliver those events asynchronously and not through the same API used for card management.

The adapter boundary should hide issuer-specific account vocabulary. Domain services should work with MasonXPay concepts such as program, cardholder, card, authorization, clearing, fee, and ledger command; adapters translate those commands to partner-specific program accounts, funding sources, cardholder accounts, and transaction objects.

Issuer adapter mutations must follow the same discipline as gateway PSP calls:

- Every external create, lock, unlock, terminate, funding, or lifecycle mutation must use a deterministic idempotency key derived from stable MasonXPay identifiers.
- Remote issuer calls must not run inside a database transaction.
- Local state changes should be split around the external call with a clear pending/succeeded/failed state, or reconciled from issuer status/webhook fetches after partial failure.
- If an issuer call succeeds but the local commit fails, retry/reconciliation must converge on the existing issuer object instead of creating a duplicate card or duplicate lifecycle mutation.

## Lifecycle APIs

Merchant-facing APIs:

- `POST /v1/cardholders`
- `GET /v1/cardholders/{cardholderId}`
- `POST /v1/card-programs`
- `GET /v1/card-programs`
- `POST /v1/cards`
- `GET /v1/cards/{cardId}`
- `GET /v1/cards`
- `POST /v1/cards/{cardId}/fund`
- `POST /v1/cards/{cardId}/withdraw`
- `POST /v1/cards/{cardId}/lock`
- `POST /v1/cards/{cardId}/unlock`
- `POST /v1/cards/{cardId}/close`
- `POST /v1/cards/{cardId}/terminate`
- `PUT /v1/cards/{cardId}/controls`

Internal/issuer-facing APIs:

- `POST /internal/issuer/authorize`
- `POST /internal/issuer/authorization-reversal`
- `POST /internal/issuer/clearing`
- `POST /internal/issuer/refund`
- `POST /internal/issuer/settlement`
- `POST /internal/issuer/webhooks/{issuerPartnerId}`

## Ledger Posting Model

The ledger remains the MasonXPay financial source of truth. The concrete postings below are the `systemOfRecord = INTERNAL` and `PREFUNDED_CARD_BALANCE` mapping used by the current simulator-backed prepaid card flow. `EXTERNAL`, `PROGRAM_BALANCE`, and `JIT_FUNDING` programs need different account mappings, but they must still converge through deterministic, balanced ledger postings and reconciliation.

Core postings:

- Fund card: move wallet balance to card available balance.
- Withdraw card: move available card balance back to wallet.
- Authorization approval: move card available balance to card hold balance.
- Authorization reversal: release hold back to card available balance.
- Clearing / presentment: consume hold and recognize issuer/network settlement payable or receivable.
- Refund / credit: increase card available balance or merchant wallet according to issuer event type.
- Close card: sweep available balance back to wallet after no open holds remain.
- Fees: book program/card fees to platform fee receivable/payable accounts when enabled.

Every posting must use deterministic event IDs from stable issuer/card identifiers.

## Fee Model

The prepaid card platform needs a fee domain even if the first implementation posts no fees. Fees must be versioned, merchant/program scoped, and evaluated at explicit workflow cutpoints so future behavior is predictable and auditable.

Suggested entities:

- `CardFeeSchedule`: merchant/mode/program-level fee configuration container.
- `CardFeeRule`: effective-dated rule with trigger, name, match expression, pricing formula, priority, stacking behavior, and visibility.
- `CardFeeScheduleVersion`: immutable published snapshot of a fee schedule and its ordered rules.
- `CardFeeQuote`: deterministic preview of fees for a workflow before posting.
- `CardFeeAssessment`: immutable record of the calculated fee for a specific card event.
- `CardFeePosting`: link from fee assessment to ledger transaction IDs.

Fee rules should support:

- fixed amount
- percentage
- percentage plus fixed amount
- tiered pricing
- minimum / maximum fee
- BIN-specific pricing
- card type pricing
- wallet tokenization channel pricing, for example Apple Pay vs Google Pay
- cross-border and FX pricing
- issuer partner / network / region filters
- merchant-specific overrides
- expression-based conditions over transaction, card, program, merchant, cardholder, and channel context

Rule matching should be flexible enough for Aviator-style expressions or an equivalent safe expression engine. The expression language must be sandboxed, deterministic, side-effect-free, and limited to an allowlisted context object. It must not call network, database, filesystem, clock, random, or mutable application APIs.

Example rules:

- Card creation: BIN `999999` costs `$0.50`; another BIN costs `$1.00`.
- Transaction fee: Google Pay `1.00% + $0.10`; Apple Pay `0.80% + $0.10`.
- Low amount surcharge: if transaction amount is below `$X`, add `$0.50`.
- Cross-currency purchase: if purchase authorization currency differs from card currency, add `0.50% + $0.10`.
- FX spread: platform receives an issuer/network exchange rate, applies a hidden markup, and exposes only the merchant-facing converted amount when the product policy allows hidden spread.

Example rule shape:

```json
{
  "ruleName": "low_amount_purchase_surcharge",
  "trigger": "AUTHORIZATION_APPROVED",
  "priority": 100,
  "matchExpression": "transaction.amount < merchant.feeParams.lowAmountThreshold && transaction.currency == card.currency",
  "feeFormula": {
    "type": "FIXED",
    "amount": "0.50",
    "asset": "USD"
  },
  "visibility": "MERCHANT_VISIBLE",
  "stacking": "STACKABLE"
}
```

```json
{
  "ruleName": "cross_currency_purchase_fee",
  "trigger": "CLEARING_POSTED",
  "priority": 110,
  "matchExpression": "transaction.purchaseCurrency != card.currency",
  "feeFormula": {
    "type": "PERCENTAGE_PLUS_FIXED",
    "percentage": "0.005",
    "fixedAmount": "0.10",
    "asset": "USD",
    "base": "transaction.settlementAmount"
  },
  "visibility": "MERCHANT_VISIBLE",
  "stacking": "STACKABLE"
}
```

```json
{
  "ruleName": "hidden_fx_spread",
  "trigger": "CLEARING_POSTED",
  "priority": 120,
  "matchExpression": "transaction.purchaseCurrency != card.currency && program.fxSpreadEnabled",
  "feeFormula": {
    "type": "PERCENTAGE",
    "percentage": "0.0125",
    "base": "transaction.settlementAmount"
  },
  "visibility": "PLATFORM_HIDDEN",
  "stacking": "STACKABLE"
}
```

Fee visibility must be explicit:

- Merchant-visible fee: appears in merchant statements, APIs, invoices, or ledger views.
- Platform-hidden economics: issuer cost, network cost, rebates, interchange share, and FX spread used for margin reporting but not disclosed as a merchant line-item unless the program contract requires it.

Initial workflow cutpoints:

- Card creation: quote and optionally post creation fee after issuer card creation succeeds.
- Card funding / withdraw: quote and optionally post load or unload fee after the ledger movement succeeds.
- Authorization approval: quote transaction fee, but usually defer posting until clearing unless the program charges at authorization time.
- Clearing / presentment: assess final transaction fee, FX fee, and network/issuer cost from settled amount and channel metadata.
- Refund / credit: decide whether to refund, reverse, or retain original fees.
- Settlement reconciliation: compare assessed fees, issuer processor fees, and posted ledger entries.

Fee posting must be idempotent on stable economic event IDs, for example `fee:{programId}:{eventType}:{issuerEventId}`. The schedule version and matched rule versions belong in the fee assessment snapshot, not the posting idempotency key; republishing a schedule must not allow the same issuer event to be charged twice. Fee rules must not perform payment decisions directly; authorization policy may reference fee quotes only when a fee changes available funds or spend limits deterministically.

Fee engine workflow:

1. A card workflow event happens, such as card creation, authorization approval, clearing, refund, or settlement.
2. Load transaction context plus card, program, merchant, cardholder, issuer partner, channel, and ledger-availability context needed for this trigger.
3. Load the active `CardFeeScheduleVersion` for merchant, mode, program, and effective time.
4. Evaluate the ordered rule list against the context.
5. Return a fee result containing matched rule IDs, calculated fee lines, merchant-visible total, platform-hidden total, and full calculation metadata.
6. Persist a `CardFeeAssessment` snapshot only at the workflow boundary that needs audit or ledger posting.
7. Post fee ledger entries from the assessment with deterministic idempotency keys.

The fee engine itself should be a stateless compute layer. It receives a context object and a published fee schedule version, and returns fee lines. It does not load configuration by itself, mutate ledger state, call issuer providers, or perform workflow decisions.

### Cross-Domain Fee Engine Direction

The fee model should be designed as a reusable domain capability because payment-gateway fees and prepaid-card fees share the same primitives: schedule, rule, rule version, match expression, formula, visibility, quote, assessment snapshot, effective dates, merchant/mode scope, and idempotent posting identity.

The first implementation should not be a separate deployable fee service. Keep workflow ownership inside the service that owns the money movement:

- `gateway-service` owns gateway payment/refund/capture fee assessment, gateway persistence, and gateway settlement integration.
- `virtual-account-service` owns prepaid-card fee assessment, VA ledger postings, issuer settlement reconciliation, and card-program accounting.
- A shared fee-engine library or module may own only stateless calculation primitives: rule matching, expression evaluation, fixed/percentage math, rounding, visibility classification, and calculation-result DTOs.

The reusable compute boundary should look like:

```text
FeeContext + FeeScheduleVersion -> FeeCalculationResult
```

Workflow-specific code remains outside that boundary. Context loading, tenant/mode authorization, deciding when to assess fees, persistence, ledger posting, provider calls, issuer calls, and reconciliation stay in the owning domain service.

Longer term, if gateway and card-program fee implementations converge and duplicated persistence/workflow code becomes real, MasonXPay can evaluate a dedicated fee service. That should be a later extraction, not the starting point, because fee posting is fund-adjacent and a premature service boundary could blur gateway vs ledger ownership.

Fee configuration must have a snapshot mechanism:

- Draft schedules can be edited freely.
- Publishing creates an immutable `CardFeeScheduleVersion`.
- Each fee assessment stores the schedule version, matched rule versions, expression text or compiled expression hash, input context digest, formula inputs, rounding policy, and output fee lines.
- Later fee configuration changes never alter historical assessments.
- Recalculation for disputes or audits must explicitly choose either the historical snapshot or the latest active schedule.

## State Machines

Card status:

- `CREATED -> ACTIVE`
- `ACTIVE -> LOCKED -> ACTIVE`
- `ACTIVE|LOCKED -> SUSPENDED`
- `ACTIVE|LOCKED|SUSPENDED -> CLOSING -> CLOSED`
- `ACTIVE|LOCKED|SUSPENDED|CLOSING -> TERMINATED`
- `ACTIVE|LOCKED -> EXPIRED`

`LOCKED` is a reversible operational/cardholder control. `SUSPENDED` is a stronger compliance or platform control and does not return directly to `ACTIVE` without a deliberately modeled reinstatement workflow. `CREATED` cards should be terminable before activation if issuer creation succeeds but activation is abandoned; direct `CREATED -> CLOSED` should be reserved for local-only simulator cleanup before an external issuer card exists.

Authorization status:

- `AUTHORIZED`
- `DECLINED`
- `REVERSED`
- `CLEARED`
- `EXPIRED`

Clearing status:

- `RECEIVED`
- `MATCHED`
- `POSTED`
- `EXCEPTION`

Settlement status:

- `EXPECTED`
- `SETTLED`
- `RECONCILED`
- `EXCEPTION`

## Card Controls

Controls should be evaluated before balance checks in the authorization path.

Initial controls:

- per-transaction amount limit
- daily/monthly velocity limits
- currency allowlist
- MCC allow/deny
- country allow/deny
- merchant descriptor allow/deny
- online/ecommerce only
- single-use card behavior
- valid-from / valid-to window

Decline reasons should be machine-readable and issuer-agnostic, for example:

- `CARD_NOT_FOUND`
- `CARD_NOT_ACTIVE`
- `CARD_LOCKED`
- `CARDHOLDER_NOT_ACTIVE`
- `PROGRAM_NOT_ACTIVE`
- `CONTROL_BLOCKED_MCC`
- `CONTROL_BLOCKED_COUNTRY`
- `LIMIT_EXCEEDED`
- `INSUFFICIENT_FUNDS`
- `AUTH_STATE_ANOMALY`

## Implementation Phases

### PPC0 - Baseline Audit and Naming

Status: [ ]

- Rename public docs from "VCC issuer" language toward "funded prepaid card platform" where appropriate.
- Document current simulated issuer behavior and the sponsor-bank/program-manager boundary.
- Confirm no raw PAN/CVV enters core services.

Acceptance:

- Docs distinguish prepaid card platform, issuer adapter, issuer bank, and simulator roles.

### PPC1 - Program and Cardholder Model

Status: [x]

- [x] Add `issuer_partner`, `card_program`, and `cardholder` tables.
- [x] Add tenant/mode scope to every table.
- [x] Add `system_of_record`, `funding_model`, external program/funding-source references, and fee schedule reference to `card_program`.
- [x] Add domain records, enums, and tenant/mode-scoped repositories for issuer partners, card programs, and cardholders.
- [x] Add APIs to create/list/get issuer partners, cardholders, and programs.
- [x] Gate card creation on active program and active cardholder status.

Acceptance:

- A merchant can create a TEST card program backed by the rail simulator and create cardholders under it.

### PPC2 - Issuer Adapter Abstraction

Status: [x]

- [x] Add `IssuerCardProviderService` and dispatcher.
- [x] Add `RAIL_SIM` adapter using existing simulator card-token behavior.
- [x] Move simulator-specific card creation behavior out of `VirtualCardService`.
- [x] Store issuer partner external IDs and external account references on virtual cards where the partner exposes them.
- [x] Require deterministic idempotency keys for issuer adapter mutations and keep remote issuer calls outside database transactions.

Acceptance:

- Card creation flows through an issuer adapter, even in simulator mode.

### PPC3 - Lifecycle APIs

Status: [x]

- [x] Add withdraw, lock, unlock, logical close, and terminate APIs.
- [x] Expand card statuses and enforce state transitions.
- [x] Keep logical close from accepting new authorizations by requiring no open authorization holds before close; a future pending-close workflow can move cards to `CLOSING` while holds settle or expire.
- [x] Keep terminate as irreversible external issuer close.
- [x] Preserve deterministic external-call idempotency keys for lock, unlock, and terminate; close remains local ledger-only in the simulator-backed flow.

Acceptance:

- Lifecycle APIs are idempotent where applicable and reject invalid transitions.

### PPC4 - Card Controls and Authorization Policy

Status: [x]

- [x] Add card/program control storage. Program defaults live on `card_program.default_controls_json`; card overrides live on `card_control_profile`.
- [x] Evaluate controls before balance checks.
- [x] Add velocity checks backed by stored authorization decisions.
- [x] Add explicit decline reasons for currency, card limit, control limit, daily amount, daily count, inactive/missing program, invalid controls, and unsupported funding models.
- [x] Ensure controls branch on `systemOfRecord` and `fundingModel` without embedding issuer-specific logic.
- [x] Defer MCC/category controls until issuer authorization requests carry merchant-category fields.

Remaining:

- Add MCC/category and merchant-country controls after issuer authorization payloads include those fields.

Acceptance:

- Authorization decisions are deterministic, replayable, and explainable without exposing sensitive card data.

### PPC5 - Authorization Reversal and Hold Expiry

Status: [x]

- [x] Add internal authorization reversal endpoint.
- [x] Add partial/full hold release posting rules.
- [x] Add hold-expiry worker for stale authorizations; it is disabled by default and enabled with `app.card-auth.hold-expiry.enabled=true`.
- [x] Update `CardAuthorizationStatus` transitions with cumulative release tracking.

Acceptance:

- Open holds are released by reversal or expiry without double-release risk.

### PPC6 - Clearing and Refund Ingestion

Status: [x]

- [x] Add clearing event tables and idempotent ingestion.
- [x] Match clearing presentment to prior open authorization holds by explicit issuer/original-authorization linkage when present; simulator fallback uses card token, card id, amount, and currency.
- [x] Post matched clearing journals.
- [x] Park no-auth, missing-linkage, amount-mismatch, and missing-original clearing events as settlement exceptions.
- [x] Add refund/credit ingestion and posting using original rail-payment linkage for refunds.
- [x] Prevent cumulative refunds from exceeding the original matched clearing amount.

Remaining:

- Partial capture and dual-message settlement remain deferred to PPC7+ settlement/reconciliation work.
- Ambiguous multi-auth matching requires issuer/original-authorization linkage from real issuer adapters; simulator exact-match fallback is intentionally conservative.

Acceptance:

- Clearing presentment can settle against holds, and unmatched events are visible in ops queues.

### PPC7 - Settlement and Reconciliation

Status: [~]

- [x] Add issuer settlement report ingestion with report-level idempotency by merchant/mode/issuer/report reference.
- [x] Reconcile issuer report lines to MasonXPay clearing events by `railPaymentId`, amount, currency, movement type, and card program.
- [x] Surface card-program settlement report line statuses by merchant/mode/program.
- [x] Add a card-program settlement reconciliation summary with issuer report, matched clearing, ledger-posted, exception, and delta totals by settlement date/currency.
- [ ] Reconcile authorization, clearing, ledger postings, and issuer settlement totals as a deeper line-to-journal accounting view.
- [ ] Reconcile MasonXPay ledger balances against issuer processor accounts or program funding balances when `systemOfRecord = EXTERNAL`.
- [ ] Add a real-issuer reconciliation processor/admin action for `card_issuer_reconciliation_task`; simulator mode only records the durable task abstraction.

Acceptance:

- Operators can explain expected vs settled amounts for a card program. Current implementation explains issuer report lines against clearing events and summarizes report-vs-clearing-vs-ledger-posted deltas; EXTERNAL system-of-record balance reconciliation remains pending.

### PPC8 - Dashboard and Operations

Status: [~]

- [x] Reorganize dashboard navigation around product/domain groups so prepaid issuing has a durable home.
- [x] Add `Issuing` navigation shell for programs, cardholders, cards, authorizations, and settlement.
- [x] Add operational Programs page for selecting configured issuer partners, card program creation, list, and pagination.
- [x] Add operational Cardholders page for create/list/pagination and merchant-owned cardholder context.
- [x] Add operational Cards page for card creation, masked-card listing, one-time simulator PAN display, funding, withdraw, lock/unlock, and card controls editing.
- [x] Add dashboard pages for merchant-scoped authorization history and settlement report/reconciliation summary views.
- [ ] Add merchant-safe exception visibility/actions after settlement exceptions have a merchant/mode-scoped external contract; current retry/discard API remains internal platform-ops only.
- Add card lifecycle actions with confirmation and audit log entries.
- Add metrics for auth approval rate, decline reasons, open holds, clearing exceptions, and settlement exceptions.

Acceptance:

- Merchants can operate simulator-backed prepaid cards from the dashboard. Current implementation provides product navigation, operational Programs management, Cardholders management, Cards create/list/fund/withdraw/lock/unlock/controls flows, authorization history, and settlement report/reconciliation summary views. Merchant-safe exception operations remain pending.

Dashboard API Boundary:

- Merchant dashboard APIs for virtual-account-service-owned domains must follow the same route as Treasury Virtual Accounts: browser -> gateway-service authenticated merchant API -> virtual-account-service internal API.
- Browser code must not call virtual-account-service directly. Gateway-service owns JWT auth, merchant membership, RBAC checks, and tenant route shape; virtual-account-service still enforces merchant/mode scope on its own reads and writes.
- Gateway proxy paths live under `/api/v1/merchants/{merchantId}/va/...` until the product needs a separate gateway namespace such as `/api/v1/merchants/{merchantId}/issuing/...`.
- Gateway RBAC actions must use the existing permission verbs (`READ`, `CREATE`, `UPDATE`, `DELETE`, `EXECUTE`), not ad hoc verbs such as `WRITE`.

### PPC9 - Fee Schedules and Economics

Status: [ ]

- Add fee schedule and fee rule model for merchant/program/BIN/channel-specific pricing.
- Add quote and assessment services with deterministic rule-version selection.
- Add ledger posting hooks for card creation, clearing, refund, settlement, and FX fees.
- Split merchant-visible fees from platform-hidden economics.

Acceptance:

- A merchant/program can carry versioned fee rules, and card workflows can calculate auditable fee assessments without making fee implementation mandatory for every workflow.

## Test Strategy

- Unit tests for state transitions, controls, decline reasons, adapter dispatch, and posting rules.
- Unit tests for fee rule selection, fee calculation, fee visibility, and deterministic idempotency keys.
- Service tests for card create/fund/withdraw/lock/unlock/close/terminate.
- Authorization tests for replay, control declines, insufficient funds, lock declines, and fail-closed behavior.
- Clearing tests for matched, partial, unmatched, amount mismatch, duplicate, and refund flows.
- Integration tests for ledger postings and tenant/mode isolation.
- Simulator smoke tests through `rail-service` and `rail-simulator`.

## Resolved Decisions

- Cardholder KYC starts as local simulator/product state, with external KYC provider references added later when a real issuer/provider requires them.
- Card programs are merchant-owned for the current PPC build. Merchant users manage their own programs, cardholders, cards, controls, and lifecycle. Platform-owned/shared programs are deferred.
- Issuer partner onboarding is platform/ops-managed, not normal merchant self-service. Commercial relationship setup, credentials, endpoints, webhook secrets, BIN/program references, and LIVE enablement belong in platform/admin operations; merchant card-program creation selects from already-configured active issuer partners.
- `RAIL_SIM` is MasonXPay's built-in default issuer for TEST prepaid-card programs. The merchant dashboard should be able to create TEST card programs without a separate issuer-partner onboarding workflow; the backend may auto-attach a merchant/mode-scoped active `RAIL_SIM` partner as platform default simulator configuration. This exception does not apply to LIVE or real issuer processors.
- Merchant dashboard APIs for virtual-account-service-owned domains go through gateway-service's authenticated merchant route, then proxy to virtual-account-service. This applies to both Treasury Virtual Accounts and prepaid-card Issuing pages.
- VA-owned prepaid issuing `/v1` APIs are internal service APIs, not public merchant APIs. They must require `X-Internal-Token` like Treasury VA APIs; merchant browser traffic must enter through gateway-service.
- Card reads, lists, controls, funding, withdrawal, and lifecycle mutations must be scoped by merchant and TEST/LIVE mode. Current card mode is derived from the linked owner ledger account until a future schema-hardening migration adds explicit card-mode composite constraints.
- MasonXPay core remains non-PCI. Raw PAN/CVV must not enter `virtual-account-service`; any future PAN/expiry access belongs behind a separate PCI vault boundary. First-stage PAN simulation can live on the rail/issuer simulator side.
- Simulator mode should first emulate issuer/processor-side card identities and PAN behavior inside the rail/issuer simulator boundary. Processor-side cardholder accounts and program funding mirrors can be added later when PPC7 reconciliation needs them.
- Card creation requires a caller-supplied idempotency key. `virtual-account-service` reserves stable local card/account IDs before the issuer call, sends a deterministic issuer idempotency key derived from merchant/mode/client key, and commits local account/card/request-state writes atomically after the issuer succeeds.
- Issuer lifecycle mutations keep issuer calls outside database transactions. If the issuer succeeds but local status persistence fails, the service records an open issuer reconciliation task. `RAIL_SIM` stops at this durable abstraction; real issuer/LIVE readiness requires a worker or admin action that fetches issuer state, applies an idempotent local correction, and marks the task resolved or failed.

## Open Questions

- Should withdraw allow partial available-balance withdrawal while holds remain open?
- Should single-use cards close immediately after authorization approval, after clearing, or after settlement?
- Which fee trigger should be implemented first: card creation fee, transaction fee, FX fee, or settlement-only fee assessment?
- Hidden FX spread and similar platform-hidden economics need per-jurisdiction legal/compliance review before any LIVE card program uses them.
- Should a suspended card ever have a reinstatement workflow, and if so what approvals and audit evidence are required?
- Should `CREATED` cards be closable locally before issuer activation, or should all post-issuer-create abandoned cards move through `TERMINATED`?
- Should VCC-specific code names remain as legacy implementation names, or should a later phase rename packages and DTOs toward generic prepaid-card terminology?
