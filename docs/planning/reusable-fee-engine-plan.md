# Reusable Fee Engine Plan

## Purpose

MasonXPay needs fee logic in more than one product surface:

- prepaid issuing needs card creation, authorization, clearing, refund, settlement, and FX fee assessment.
- payment gateway needs payment, capture, refund, provider, payment-method, routing, and settlement economics.

The shared part should be a reusable, stateless fee engine. Product services own context loading, tenant/mode authorization, fee schedule persistence, assessment snapshots, ledger posting, provider calls, and operational workflows.

This plan is the detailed design for PPC9 and the long-term gateway-service fee reuse path. The prepaid-card phase tracker keeps only the PPC9 summary in [prepaid-card-program-platform-plan.md](prepaid-card-program-platform-plan.md).

## Goals

- Add a reusable backend fee engine module that can be used by `virtual-account-service` and `gateway-service`.
- Support flexible rule matching with an expression parser, using an Aviator-style model for whitelisted context fields.
- Support fixed and percentage fee components under named rules.
- Return visible and hidden fee lines, totals, and matched rule metadata.
- Keep fee calculation deterministic, auditable, and testable.
- Preserve immutable fee assessment snapshots so later fee configuration changes do not rewrite historical economics.
- Keep ledger posting idempotency stable across fee schedule republishes.

## Non-Goals

- No payment, authorization, routing, or issuer decisions inside the fee engine.
- No database, HTTP, provider, issuer, ledger, tenant, RBAC, or dashboard ownership inside the fee engine.
- No merchant-facing fee configuration UI in the first implementation.
- No hidden FX spread for LIVE programs until legal/compliance review is complete per jurisdiction.
- No raw PAN, CVV, provider secrets, or raw provider payloads in fee contexts or snapshots.

## Module Boundary

Create a backend module such as:

`backend/fee-engine`

Package:

`com.masonx.feeengine`

The module computes only:

- input: normalized fee context + immutable rule definitions.
- output: fee assessment result with fee lines, totals, matched rules, and diagnostics.

The module must not:

- load fee schedules from DB.
- persist assessment snapshots.
- post ledger entries.
- call providers, issuers, rails, or gateway connectors.
- check merchant membership or RBAC.
- mutate payment/card/account state.

## Ownership By Service

### `virtual-account-service`

Owns prepaid-card fee usage:

- card-program or merchant fee schedule persistence.
- prepaid card context loading.
- card fee assessment snapshot persistence.
- prepaid ledger posting hooks.
- issuer settlement and processor-fee reconciliation.

Example event types:

- `CARD_CREATE`
- `CARD_AUTH`
- `CARD_CLEARING`
- `CARD_REFUND`
- `CARD_SETTLEMENT`
- `CARD_FX`

### `gateway-service`

Later owns payment-gateway fee usage:

- merchant/provider/payment-method fee schedule persistence.
- payment/refund/capture context loading.
- gateway fee assessment snapshots.
- payment economics projections and optional ledger/settlement effects.

Example event types:

- `PAYMENT_CREATE`
- `PAYMENT_CONFIRM`
- `CAPTURE`
- `REFUND`
- `DISPUTE`
- `PROVIDER_SETTLEMENT`

## Fee Engine Model

### Fee Context

The owning service builds a normalized context map from safe, whitelisted fields.

Prepaid examples:

- `eventType`
- `merchantId`
- `mode`
- `programId`
- `bin`
- `channel`
- `walletType`
- `amount`
- `amountUsd`
- `transactionCurrency`
- `cardCurrency`
- `merchantCountry`
- `cardholderCountry`
- `isCrossBorder`
- `isFx`

Gateway examples:

- `eventType`
- `merchantId`
- `mode`
- `provider`
- `connectorAccountId`
- `paymentMethodType`
- `cardBrand`
- `amount`
- `currency`
- `country`
- `routePolicyId`
- `captureMethod`

Context snapshots must be sanitized. They must not include raw PAN, CVV, secrets, raw provider payloads, raw webhook bodies, signature headers, or unnecessary PII.

### Fee Schedule

A fee schedule is the versioned container selected by the owning service.

Expected fields:

- `scheduleId`
- `merchantId`
- `mode`
- optional product scope: `programId`, `provider`, `connectorAccountId`, or future platform scope.
- `version`
- `status`: `DRAFT`, `ACTIVE`, `ARCHIVED`
- `effectiveFrom`
- `effectiveTo`
- `rules`

The fee engine receives an immutable schedule version. It does not decide which version is active.

### Fee Rule

Expected fields:

- `ruleId`
- `ruleVersion`
- `name`
- `priority`
- `matchExpression`
- `components`
- `visibility`: default for generated lines.
- optional `stopProcessing`
- optional `metadata`

Rules are evaluated in deterministic order, such as priority then rule ID.

### Fee Component

Supported first-pass component types:

- `FIXED`: fixed amount in a currency.
- `PERCENTAGE`: percentage of a context amount field.

Expected fields:

- `componentId`
- `name`
- `type`
- `amount` for fixed components.
- `currency` for fixed components.
- `rateBps` or decimal percent for percentage components.
- `basisField`, such as `amount` or `amountUsd`.
- optional min/max amount.
- `visibility`: `MERCHANT_VISIBLE`, `PLATFORM_HIDDEN`, or `INTERNAL_ONLY`.

First-pass limits:

- Component currency must match the selected fee currency. For prepaid-card PPC9, selected fee currency is the card/account currency. Fixed components in a different currency are invalid until the FX conversion abstraction exists.
- Component-level min/max is supported first. Rule-level or assessment-level caps/floors, such as "1% with total fee min 0.50 and max 5.00", are deferred.

## Rounding Policy

Percentage fees must be deterministic across calculation, persistence, ledger posting, and reconciliation.

First-pass policy:

- Use `BigDecimal` for all monetary calculation.
- Calculate percentage components from the declared `basisField`.
- Preserve the raw calculated amount before rounding.
- Round each component independently to the fee currency/account asset scale.
- Default rounding mode is `HALF_UP`.
- Persist rounding mode, rounding scale, basis amount, raw calculated amount, and rounded amount in the assessment snapshot line.

Schedule-level or component-level rounding overrides are deferred until there is a real provider, issuer, or jurisdiction requirement.

## Expression Matching

Use a mature expression parser rather than custom parsing. Aviator-style expressions are a good fit if dependency, Java 21, and sandbox behavior are acceptable.

Rules:

- Expressions receive only the normalized context map.
- Expressions return boolean match/no-match.
- Expressions cannot call DB, HTTP, ledger, provider, issuer, file, reflection, or mutation APIs.
- Function support must be explicit and small.
- Missing fields must fail closed or evaluate predictably.
- All expressions must be validated before activation.

AviatorScript must be sealed before FE2 is considered complete:

- Use a dedicated `AviatorEvaluatorInstance`; do not use a mutable global evaluator.
- Disable method invocation, reflection-like access, and access to arbitrary object methods.
- Do not expose domain objects to expressions; expose only normalized primitive/string/boolean/decimal values.
- Do not expose clock, random, environment, system, IO, collection mutation, or side-effecting functions.
- Register only a small allowlist of pure functions needed by fee rules.

Each product integration must define a fee context schema:

- allowed field names.
- field types.
- required fields per event type.
- nullable fields and missing-field behavior.

Expression validation must check both syntax and referenced field names against that schema before a rule can be activated. Unknown fields are configuration errors, not silent non-matches.

Example match expressions:

```text
amountUsd < 10
transactionCurrency != cardCurrency
eventType == 'CARD_AUTH' && walletType == 'APPLE_PAY'
eventType == 'PAYMENT_CONFIRM' && provider == 'STRIPE' && paymentMethodType == 'CARD'
```

## Example Fee Rules

### Small-Amount Additional Fee

Case:

- amount smaller than `X USD`
- add fixed `0.50 USD`

Rule shape:

```json
{
  "ruleId": "rule_small_amount",
  "ruleVersion": 1,
  "name": "small_amount_surcharge",
  "priority": 100,
  "matchExpression": "amountUsd < 10",
  "components": [
    {
      "componentId": "fixed_small_amount",
      "name": "small_amount_fixed",
      "type": "FIXED",
      "amount": "0.50",
      "currency": "USD",
      "visibility": "MERCHANT_VISIBLE"
    }
  ]
}
```

### FX Purchase Authorization Fee

Case:

- purchase authorization currency is not the same as card currency.
- add `0.5% + 0.10`.

Rule shape:

```json
{
  "ruleId": "rule_fx_auth",
  "ruleVersion": 3,
  "name": "fx_purchase_auth_fee",
  "priority": 200,
  "matchExpression": "eventType == 'CARD_AUTH' && transactionCurrency != cardCurrency",
  "components": [
    {
      "componentId": "fx_percent",
      "name": "fx_markup_percent",
      "type": "PERCENTAGE",
      "basisField": "amount",
      "rateBps": 50,
      "visibility": "PLATFORM_HIDDEN"
    },
    {
      "componentId": "fx_fixed",
      "name": "fx_fixed",
      "type": "FIXED",
      "amount": "0.10",
      "currency": "USD",
      "visibility": "PLATFORM_HIDDEN"
    }
  ]
}
```

This is an example of model flexibility, not a decision that hidden FX markup is allowed for LIVE programs.

## Fee Engine Workflow

1. An economic event happens.
2. The owning service validates tenant/mode/RBAC and loads domain context.
3. The owning service loads the active fee schedule version for merchant/product/event scope.
4. The owning service builds a sanitized fee context.
5. The stateless fee engine evaluates rules in deterministic order.
6. The engine returns fee lines, matched rule references, visible totals, hidden totals, and diagnostics.
7. The owning service persists an immutable assessment snapshot.
8. The owning service optionally posts ledger entries using a stable idempotency key.
9. Reconciliation compares snapshots, posted ledger entries, and provider/issuer processor fees.

## Assessment Snapshot

Snapshots prevent fee configuration changes from changing historical interpretation.

Expected persisted fields in the owning service:

- `assessmentId`
- `merchantId`
- `mode`
- `eventType`
- `eventId`
- product scope: program, card, payment, provider, connector, or route references.
- `scheduleId`
- `scheduleVersion`
- matched `ruleId` + `ruleVersion` list.
- sanitized context snapshot.
- fee lines with names, visibility, currency, amount, component ID, and calculation metadata.
- per-line rounding metadata: rounding mode, rounding scale, basis amount, raw calculated amount, and rounded amount.
- visible totals by currency.
- hidden totals by currency.
- created timestamp.

Assessment rows should be immutable except for operational annotations or reversal links if a workflow later requires them.

## Ledger Posting Boundary

The fee engine does not post ledger entries.

The owning service posts fees from a persisted assessment. Posting idempotency must be based on the stable economic event, not the fee rule version.

Example key:

```text
fee:{merchantId}:{mode}:{eventType}:{eventId}
```

Do not include schedule version or rule version in the posting idempotency key. Those belong in the assessment snapshot.

Prepaid FE5 must reuse the existing `LedgerFacade` and net-zero posting-rule pattern. It must not introduce an alternate ledger posting path.

Credit-side account mapping should reuse existing platform fee account types where applicable, such as `PLATFORM_FEE_RECEIVABLE` and `FEE_INCOME`.

Debit-side funding source is event-specific and must be decided before FE5 implementation. Candidate sources include merchant `WALLET`, card `PREPAID_CARD`, or a merchant fee receivable account. The first implementation should explicitly document the debit source for `CARD_CREATE` and `CARD_CLEARING` before posting fees.

## Roadmap

### FE0 - Boundary and Documentation

Status: [x]

- [x] Define reusable fee-engine boundary and service ownership model.
- [x] Link PPC9 to this dedicated plan.
- [x] Use Google AviatorScript as the first expression library, wrapped behind a fee-engine evaluator interface.

### FE1 - Module Skeleton

Status: [x]

- [x] Add `backend/fee-engine` Maven module.
- [x] Define fee context, schedule, rule, component, assessment, line, visibility, and error types.
- [x] Add pure unit tests without Spring or DB.

### FE2 - Expression Matching and Calculation

Status: [x]

- [x] Add expression evaluator behind an internal interface.
- [x] Use a sealed Aviator evaluator instance with sandbox mode, class allowlists closed, method/class features disabled, no domain objects, and no expression functions.
- [x] Support fixed and percentage components.
- [x] Apply deterministic `BigDecimal` rounding per component using persisted rounding mode and scale.
- [x] Support deterministic rule ordering.
- [x] Add tests for small-amount and FX examples.
- [x] Add validation for missing fields, invalid expressions, unknown context fields, cross-currency fixed components, and unsupported component config.

### FE3 - Prepaid Schedule Persistence

Status: [ ]

- Add `virtual-account-service` tables for prepaid fee schedules and immutable versions.
- Scope by merchant/mode and optional card program/BIN/channel.
- Add service APIs for internal/admin seed or management, not merchant dashboard UI yet.

### FE4 - Prepaid Assessment Snapshots

Status: [ ]

- Add prepaid fee assessment and assessment-line persistence.
- Store sanitized context snapshots and matched rule versions.
- Add service tests for immutable historical interpretation.

### FE5 - Prepaid Ledger Posting Hooks

Status: [ ]

- Post fee ledger entries from persisted assessments.
- Use stable event-based idempotency keys.
- Reuse `LedgerFacade` and PostingRule net-zero infrastructure; credit platform fee accounts such as `PLATFORM_FEE_RECEIVABLE` / `FEE_INCOME` where appropriate.
- Decide and document debit funding source per trigger before implementation.
- Start with one narrow trigger, preferably card creation or clearing, before auth-time balance-impacting fees.

### FE6 - Gateway-Service Adoption

Status: [ ]

- Add gateway fee schedule persistence and snapshots after prepaid foundation proves the reusable engine.
- Reuse `fee-engine` calculation types without sharing gateway persistence or payment workflow ownership.

### FE7 - Dashboard/Admin Preview

Status: [ ]

- Add validation/preview tooling before any merchant-facing configuration UI.
- Show matched rules, fee lines, visible/hidden totals, and context fields.
- Keep LIVE activation behind admin/compliance controls.

## Test Strategy

- Unit tests for expression matching, missing fields, fixed fees, percentage fees, visibility totals, rule ordering, and invalid config.
- Service tests in `virtual-account-service` for schedule version selection, snapshot immutability, and ledger idempotency.
- Later gateway tests for payment/refund/capture fee contexts.
- No E2E dashboard dependency for FE1-FE2.

## Resolved Decisions

- Expression library: use Google AviatorScript (`https://github.com/killme2008/aviatorscript`) first, wrapped behind a `FeeExpressionEvaluator` abstraction so the rest of the engine is not coupled to Aviator APIs.
- First prepaid persisted triggers: start with `CARD_CREATE` and `CARD_CLEARING`.
- Fee currency: use the prepaid-card account currency/card currency as the fee currency. If transaction currency differs and conversion is required, leave an FX abstraction cutpoint and fail explicitly in the current stage instead of silently calculating an incorrect fee.
- Rounding: first-pass percentage fees round each component with `HALF_UP` to the fee currency/account asset scale, and snapshots persist the raw and rounded calculation details.
- Initial seed fee examples: card creation fee is `1.00 USD` per card; authorization-style fee rule is `1% + 0.10 USD`. Actual auth-time charging can remain deferred until balance-impacting authorization behavior is intentionally designed.
- Shared engine, local ownership first: the reusable `fee-engine` module owns computation only. Prepaid persistence starts in `virtual-account-service`; gateway persistence can adopt the same schema shape later without sharing payment/card workflow ownership.

## Open Questions

- Once both prepaid issuing and gateway-service use cases are active, should fee schedule persistence remain service-local with a shared schema shape, or move to a centrally owned economics/fee persistence service?
- Which fee categories need compliance disclosure controls before LIVE use?
- For `CARD_CREATE` and `CARD_CLEARING`, should the debit source be merchant `WALLET`, card `PREPAID_CARD`, or a merchant fee receivable account?
