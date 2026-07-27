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

## Expression Matching

Use a mature expression parser rather than custom parsing. Aviator-style expressions are a good fit if dependency, Java 21, and sandbox behavior are acceptable.

Rules:

- Expressions receive only the normalized context map.
- Expressions return boolean match/no-match.
- Expressions cannot call DB, HTTP, ledger, provider, issuer, file, reflection, or mutation APIs.
- Function support must be explicit and small.
- Missing fields must fail closed or evaluate predictably.
- All expressions must be validated before activation.

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

## Roadmap

### FE0 - Boundary and Documentation

Status: [~]

- [x] Define reusable fee-engine boundary and service ownership model.
- [x] Link PPC9 to this dedicated plan.
- [ ] Decide exact Java expression library after dependency review.

### FE1 - Module Skeleton

Status: [ ]

- Add `backend/fee-engine` Maven module.
- Define fee context, schedule, rule, component, assessment, line, visibility, and error types.
- Add pure unit tests without Spring or DB.

### FE2 - Expression Matching and Calculation

Status: [ ]

- Add expression evaluator behind an internal interface.
- Support fixed and percentage components.
- Support deterministic rule ordering.
- Add tests for small-amount and FX examples.
- Add validation for missing fields, invalid expressions, and unsupported component config.

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

## Open Questions

- Which expression library should be selected: AviatorScript or another Java expression evaluator?
- Should the first persisted prepaid trigger be card creation, clearing, or settlement-only assessment?
- How should multi-currency percentage fees choose output currency when the basis and fee currency differ?
- Which fee categories need compliance disclosure controls before LIVE use?
- Should gateway-service and virtual-account-service eventually share fee schedule persistence, or keep separate persistence with a shared compute module only?
