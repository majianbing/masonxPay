# Gateway ID Standardization — Refactor Plan

Status: **In progress — Phase 10 complete**.

This plan is driven by `backend/gateway-service`, but it includes one cross-module foundation step: make ID prefixes centrally manageable in `common` and align the modules that already mint prefixed IDs. The goal is to move gateway-created resource identifiers toward the shared MasonXPay ID format introduced in `common`:

```text
{prefix}_{snowflakeId}
```

Examples already used by later modules:

- `virtual-account-service`: `ac_`, `le_`, `tx_`, `va_`, `vc_`
- `rail-service`: `rp_`, `rd_`, `iso_`, `corr_`, `rtask_`, `evt_`

Gateway currently predates this convention and uses UUIDs broadly in database primary keys, Java domain models, DTOs, repository signatures, dashboard routes, and external API contracts. VA and rail already use prefixed IDs, but their prefixes are scattered as raw string literals.

## Goals

- Standardize newly exposed gateway resource IDs on prefixed Snowflake strings.
- Move ID prefix definitions into a shared `common` enum/registry so prefixes are discoverable and reviewable.
- Replace raw prefix literals in `virtual-account-service` and `rail-service` with the shared registry without changing their existing persisted ID values.
- Protect dashboard and external API compatibility during migration.
- Avoid a risky one-shot primary-key rewrite across the payment core.
- Keep tenant/mode scoping, sharding, idempotency, Kafka/outbox behavior, and payment-state authority rules intact.
- Fix adjacent money-moving idempotency-key issues found during analysis.

## Non-Goals

- Do not migrate existing VA or rail persisted ID values; they already use the shared generator style.
- Do not rewrite all gateway tables in one migration.
- Do not weaken existing UUID tenant identifiers unless a separate tenant-ID migration is explicitly designed.
- Do not change provider IDs, provider payment references, webhook event IDs received from PSPs, or opaque security tokens unless they are MasonXPay resource IDs.

## Current Gateway Surface

Code scan summary:

| Surface | Count | Notes |
|---|---:|---|
| JPA domain classes with `@Id` | 43 | Includes read models and processed-event tracking tables. |
| Entities using `@GeneratedValue(strategy = GenerationType.UUID)` | 40 | App/JPA-generated UUID primary keys. |
| Manual gateway resource UUID generation | 1 | `PaymentIntent` ID is assigned with `UUID.randomUUID()` before shard routing. |
| Migration `gen_random_uuid()` occurrences | 36 | Includes primary-key defaults plus data backfill/default usage. |
| UUID primary-key declarations in migrations | 45 | Includes app-assigned, DB-generated, and derived primary keys. |
| Production `UUID.randomUUID()` call sites | 15 | Mix of resource IDs, trace IDs, tokens, storage keys, simulator IDs, and idempotency keys. |

The important conclusion: this is not only an ID helper replacement. Gateway IDs are UUID-shaped throughout persistence, public DTOs, controller path variables, repositories, tests, dashboard usage, SDK expectations, Redis idempotency routes, Kafka payloads, read projections, and sharded payment tables.

## Compatibility Strategy

Because gateway has a dashboard and external API, use an additive transition first:

1. Keep existing UUID primary keys and existing UUID API fields working.
2. Add public prefixed string IDs as stable external IDs.
3. Teach read/write APIs to accept the new public ID while preserving old UUID routes during a deprecation window.
4. Move dashboard and SDKs to public IDs.
5. Only then consider whether physical primary keys should be migrated from UUID to string.

Recommended default: **public external IDs first, primary-key migration later only if still valuable**.

Rationale:

- It avoids rewriting every foreign key and sharded table immediately.
- It avoids breaking existing API clients and dashboard routes.
- It gives us a clear testable boundary: internal persistence can remain UUID while external contracts become standardized.
- It still achieves the main product-facing benefit: IDs are consistent, prefixed, sortable-ish, and recognizable by resource type.

## Proposed ID Model

Add an `external_id` column to gateway-owned resource tables that need public or cross-service identity.

Column shape:

```sql
external_id VARCHAR(40)
CREATE UNIQUE INDEX ... ON table_name(external_id) WHERE external_id IS NOT NULL;
```

Use `VARCHAR(64)` where the ID can cross service boundaries that already use wider strings. Prefixes should stay short and resource-specific.

Migration shape:

- Add `external_id` as nullable first.
- Backfill existing rows.
- Add a partial unique index while current writers are still being upgraded.
- After every creation path assigns `external_id`, promote the column to `NOT NULL` and keep uniqueness as a hard invariant.

Java shape:

- Entity primary key remains `UUID id` in phase 1.
- Entity gains `String externalId`.
- DTOs expose `id` as the external ID in new API versions, or expose both during migration:
  - `id`: current UUID for backward compatibility in current API.
  - `externalId`: new prefixed string.
- New endpoints should prefer `{externalId}` path variables.

## Prefix Registry

Prefixes should not be scattered as raw string literals. Add a shared enum/registry in `common`, then have services call the generator through typed prefix values.

Draft shape:

```java
public enum MasonXIdPrefix {
    PAYMENT_INTENT("pi_"),
    REFUND("rf_"),
    VA_ACCOUNT("ac_"),
    LEDGER_ENTRY("le_"),
    RAIL_PAYMENT("rp_"),
    EVENT("evt_");

    private final String value;
}
```

Exact naming is open, but the registry should:

- live in `common`, likely under `com.masonx.common.id`,
- expose the literal prefix string through a small method such as `value()` or `prefix()`,
- include all prefixes currently minted by gateway, VA, and rail,
- be covered by tests for uniqueness and valid format,
- avoid putting service business logic in `common`,
- require the caller to choose the correct enum value at the call site or through a service-local wrapper.

`SnowflakeIdGenerator.generate(String prefix)` can remain for low-level flexibility, but service code should prefer a typed wrapper:

```java
idGenerator.generate(MasonXIdPrefix.PAYMENT_INTENT.prefix());
```

If a wrapper improves readability, it should live in the owning service module. It should not live in `common` if it decides resource semantics.

Draft gateway prefixes:

| Resource | Prefix | Notes |
|---|---|---|
| PaymentIntent | `pi_` | Highest priority; merchant-facing and cross-service. |
| PaymentRequest / attempt | `pr_` | Payment attempt record. |
| Refund | `rf_` | Merchant-facing money movement. |
| ProviderAccount | `pa_` | Dashboard connector account. |
| PaymentLink | `plink_` | Avoid conflict with payment intent `pi_`. |
| PaymentToken | `ptok_` | Internal/provider token wrapper, not raw card data. |
| PaymentInstrument | `pinst_` | Routing and billing instrument reference. |
| WebhookEndpoint | `whe_` | Merchant-facing endpoint config. |
| GatewayEvent / OutboxEvent | `evt_` | Align with contracts/rail event convention. |
| WebhookDelivery | `whd_` | Delivery attempt/resource. |
| RoutingRule | `rr_` | Legacy routing rule. |
| RoutePolicy | `rpol_` | Policy v2. |
| RoutePolicyRoute | `rroute_` | Policy route. |
| RoutePolicyStep | `rstep_` | Policy step. |
| RoutePolicyAuditLog | `rpa_` | Audit log. |
| RoutingAttribute | `rattr_` | Routing intelligence metadata. |
| ScheduledRetryJob | `retry_` | Retry job. |
| BillingCustomer | `cus_` | External customer-like object. |
| CustomerPaymentMethod | `cpm_` | Billing attachment record. |
| Subscription | `sub_` | Existing token prefix also uses `sub_`; checkout link should differ. |
| SubscriptionItem | `si_` | Subscription line item. |
| SubscriptionCheckoutLink | `scl_` | Resource ID; token stays separate. |
| Invoice | `inv_` | Billing invoice. |
| InvoicePaymentAttempt | `ipa_` | Billing payment attempt. |
| Dispute | `dp_` | Aligns with common dispute shorthand. |
| DisputeEvidenceFile | `def_` | Evidence file metadata. |
| MerchantAuditLog | `mal_` | Merchant audit row. |
| GatewayLog | `glog_` | Operational log row. |
| ApiKey | `ak_` | Secret key material remains separate and never logged. |
| User | `usr_` | Portal user. |
| Merchant | `mer_` | Merchant identity. |
| MerchantUser | `mu_` | Membership row. |
| Organization | `org_` | Organization identity. |
| OrganizationUser | `ou_` | Organization membership row. |
| RefreshToken | `rt_` | Check whether this should stay opaque/internal instead. |
| InviteToken | `it_` | Token secret remains separate; row ID only. |
| AdminUser | `adm_` | Platform admin identity. |
| AdminAuditLog | `aal_` | Admin audit row. |

Resolved prefix/API decisions:

- `common` exposes the prefix enum/registry; callers choose the enum value directly or through service-local wrappers.
- Tenant-facing gateway IDs (`usr_`, `mer_`, `org_`) are in scope for the first gateway external-ID wave.
- The external API stays same-version during migration: add `externalId` fields and compatibility lookups without introducing `/v2`.

Open prefix/API questions:

- Should security-adjacent internal rows (`RefreshToken`, `InviteToken`) receive public external IDs at all?
- Should event-like rows use `evt_` everywhere, or distinguish `out_` for outbox and `gevt_` for gateway events?
- What deprecation period is acceptable for UUID route params in dashboard/external API?

## Existing VA/Rail Prefixes To Register

These prefixes already exist in production code and should be moved behind the shared registry without changing their stored value format:

| Module | Prefix | Meaning |
|---|---|---|
| `virtual-account-service` | `ac_` | VA account. |
| `virtual-account-service` | `le_` | Ledger entry. |
| `virtual-account-service` | `tx_` | Ledger transaction. |
| `virtual-account-service` | `tx_rail_` | Rail settlement ledger transaction. |
| `virtual-account-service` | `tx_fund_` | VCC funding ledger transaction. |
| `virtual-account-service` | `tx_close_` | VCC close/sweep ledger transaction. |
| `virtual-account-service` | `va_` | VCC backing account. |
| `virtual-account-service` | `vc_` | Virtual card. |
| `rail-service` | `rp_` | Rail payment. |
| `rail-service` | `rd_` | Rail routing decision. |
| `rail-service` | `iso_` | ISO 8583 log row. |
| `rail-service` | `corr_` | Network correlation row. |
| `rail-service` | `rtask_` | Reversal task. |
| `rail-service` | `evt_` | Rail-published event. |
| `rail-service` | `m20_` | ISO 20022 message ID. |
| `rail-service` | `ins_` | ISO 20022 instruction ID. |
| `rail-service` | `e2e_` | ISO 20022 end-to-end ID. |
| `rail-service` | `i20_` | ISO 20022 log row. |

This step should be behavior-preserving: generated IDs keep the same literal prefixes, and table schemas remain unchanged.

## Phase Plan

Legend: pending until approved.

| Phase | Scope | Verification |
|---|---|---|
| 0 | Decide compatibility policy, prefix registry, and unresolved deprecation/internal-row questions. Tenant-facing gateway IDs are in scope; API migration stays same-version with additive fields/lookups. | Plan approved; no code. |
| 1 | Add shared prefix enum/registry in `common`; register existing VA, rail, and proposed gateway prefixes; add uniqueness/format tests. | Complete: `cd backend && mvn -pl common test` passed. |
| 2 | Replace VA and rail raw prefix literals with the shared registry. This is behavior-preserving and does not alter stored IDs or schemas. | Complete: `cd backend && mvn -pl virtual-account-service,rail-service -am test` passed outside sandbox; sandboxed run failed on Mockito/Byte Buddy self-attach. |
| 3 | Add gateway Snowflake config and a central `GatewayIdService` using the shared prefix registry. No persistence behavior changes yet except tests for prefix generation. | Complete: `cd backend && mvn -pl gateway-service -am test` passed. |
| 4 | Add nullable `external_id` to highest-value payment core tables: payment intents, payment requests, refunds, outbox events, gateway events, webhook deliveries. Backfill existing rows deterministically enough for uniqueness and add partial unique indexes while writers are still being upgraded. | Complete: `cd backend && mvn -pl gateway-service -am test` passed. |
| 5 | Assign `external_id` in gateway writers; expose `externalId` in payment/refund/webhook DTOs while preserving current UUID `id`; add lookup helpers by external ID; mirror payment intent external IDs into the read model. | Complete: `cd backend && mvn -pl gateway-service -am test` passed. |
| 6 | Add same-version external-ID route compatibility for payment intent API/dashboard routes and webhook delivery replay while keeping UUID route aliases. | Complete: `cd backend && mvn -pl gateway-service -am test` passed. |
| 7 | Move dashboard route state, API client calls, and SDK response handling to external IDs. | Complete: `cd backend && mvn -pl gateway-service -am test`, `cd dashboard && npm run build`, and `cd sdk/server && npm run build` passed. |
| 8 | Fix random money-moving idempotency keys and add regression coverage. | Complete: no random provider-idempotency key matches remain; `cd backend && mvn -pl gateway-service -am test` passed. |
| 9 | Extend external IDs to remaining dashboard/admin resources: disputes, dispute evidence files, scheduled retry jobs, and webhook endpoints. UUID routes remain accepted while dashboard actions prefer external IDs. | Complete: `cd backend && mvn -pl gateway-service -am test` and `cd dashboard && npm run build` passed. |
| 10 | Deprecation window: document UUID fields/routes as legacy. | Complete: API compatibility/deprecation policy added to this plan and SDK docs; no runtime behavior change. |
| 11 | Optional later migration: replace UUID primary keys with string primary keys where worth the cost. | Separate design and data migration plan required. |

## High-Priority Implementation Details

### Gateway ID Service

Add a gateway-local wrapper around `SnowflakeIdGenerator` instead of scattering resource-specific method calls. The wrapper must use the shared prefix registry, not raw string literals:

```java
public String paymentIntentId() {
    return idGenerator.generate(MasonXIdPrefix.PAYMENT_INTENT.prefix());
}
```

This keeps prefix changes local and makes tests clearer.

Gateway should mirror other modules:

- `gateway.id.node-id`
- `GATEWAY_NODE_ID`
- `SnowflakeIdGenerator` singleton bean

Node IDs must be unique across all running app instances that share an ID namespace.

### VA/Rail Prefix Cleanup

Before gateway starts adding new public IDs, align existing prefixed-ID producers:

- Replace VA raw prefixes such as `"ac_"`, `"le_"`, `"tx_"`, `"va_"`, `"vc_"` with `MasonXIdPrefix`.
- Replace rail raw prefixes such as `"rp_"`, `"rd_"`, `"iso_"`, `"corr_"`, `"rtask_"`, `"evt_"`, `"ins_"` with `MasonXIdPrefix`.
- Do not rename existing prefixes.
- Do not run data migrations for VA or rail.
- Add compile-time usage tests only where useful; the important common tests are uniqueness and format validation for the enum.

### Backfill

Do not derive public IDs from UUID text. Backfill with generated prefixed IDs.

For existing databases, backfill can be done in application migration code or SQL helper functions, but it must:

- produce unique values,
- use the correct prefix per table,
- be idempotent if rerun safely by Flyway semantics,
- not expose ordering assumptions stronger than Snowflake provides.

### API Shape

Avoid abruptly changing existing `id` fields from UUID to string in current responses. Prefer:

```json
{
  "id": "uuid-for-compat",
  "externalId": "pi_123456789"
}
```

Then define a later API version or deprecation step where `id` becomes the prefixed public ID.

### ID Compatibility And Deprecation Policy

Current same-version API behavior:

- `id` remains the internal UUID field for compatibility.
- `externalId` is the canonical public resource ID for merchant-facing references, dashboard URLs, SDK examples, logs, support tickets, and follow-up API calls.
- Route parameters that were upgraded in phases 6-9 accept either UUID or `externalId`.
- Dashboard and SDK code should send `externalId ?? id` during the compatibility window.

UUID route parameters and UUID response `id` fields are now **legacy compatibility surface** for external clients. They remain supported during this branch, but new code should not introduce merchant-facing UUID URLs for resources that expose `externalId`.

Deprecation stages:

1. **Current same-version window:** return both `id` and `externalId`; accept either identifier in upgraded routes; prefer `externalId` in docs, SDK examples, and dashboard links.
2. **Future warning window:** add API documentation warnings and optional response/request deprecation headers for UUID route usage after API docs are formalized.
3. **Future major API version:** make public `id` semantics explicit as prefixed IDs, or remove legacy UUID route aliases only after client migration is measured.

Do not remove UUID primary keys or internal UUID fields as part of this phase. Physical primary-key migration remains Phase 11 and requires a separate design.

### Dashboard

Dashboard migration should happen after API supports dual lookup.

Expected dashboard changes:

- Route params should move from UUIDs to external IDs.
- Query keys should use external IDs for resources.
- Lists should display external IDs where merchants need support/reference values.
- Any copy-to-clipboard or search behavior should prefer external IDs.

### SDKs

SDKs should preserve backward compatibility:

- Add `externalId` fields first.
- Keep UUID `id` fields typed as existing.
- In a later major version, rename or change `id` semantics if desired.

## Idempotency Issues To Fix

During the ID scan, two random idempotency-key call sites were found in money-moving paths:

- `SubscriptionCheckoutPaymentService`: `sub-{subscriptionId}-{randomUuid}`
- `PublicPaymentController`: `pl-{paymentLinkId}-{randomUuid}`

These should be changed to deterministic keys derived from stable business identifiers before or during the payment-core ID phase.

Candidate deterministic keys:

- Payment link checkout: derive from payment link ID plus created payment intent ID or a stable checkout/session token.
- Subscription checkout: derive from subscription ID plus invoice ID or payment intent ID.

Do not use random UUIDs for provider idempotency keys on charge/capture/refund/recurring payment execution.

**Resolution status:** `SubscriptionCheckoutPaymentService` and `PublicPaymentController.checkout()` (card flow) are safe — both gate on an atomic `claimLink`/`claimLink`-equivalent before generating any key, so a per-call random suffix embedded in the key cannot be replayed by a retry. `PublicPaymentController.prepareStripe()` (redirect-based checkout: iDEAL, Sofort, Amazon Pay) was **not** actually fixed by the Phase 8 pass below despite the Phase 8 note claiming it — it still sent a fresh `UUID.randomUUID()`-derived idempotency key to Stripe on every call with no DB state check, and had no claim guard, so a page reload/retry before the redirect completed could mint a duplicate live Stripe PaymentIntent for the same link. This was found in a post-Phase-10 code review and fixed on 2026-07-01 (see Changelog) with a deterministic per-attempt key plus an idempotent DB lookup that resumes an in-flight attempt instead of creating a new one.

## Risks

| Risk | Mitigation |
|---|---|
| Breaking existing external API clients | Add `externalId` and dual lookup before changing `id` semantics. |
| Dashboard broken by mixed UUID/string routes | Migrate dashboard only after backend accepts both forms. |
| Sharded payment tables and registry mismatch | Start with additive `external_id`; leave shard routing on existing UUID until separately designed. |
| Kafka/Redis payload mismatch | Version payloads or add fields additively; keep UUID fields until consumers move. |
| Too many prefixes become inconsistent | Centralize prefixes in one gateway service and document registry here. |
| `common` becomes a business-domain dumping ground | Keep the shared type to prefix names/literals and small format helpers only; service ownership stays outside `common`. |
| Accidentally changing VA/rail persisted ID format | Register existing literals first and replace call sites without changing prefix values or schema. |
| Token/resource ID confusion | Keep security tokens opaque and separate from row/resource IDs. |
| Existing data migration complexity | Backfill external IDs first; postpone physical PK replacement. |

## Review Questions

1. Should the first implementation target external IDs only, or do we want to commit now to full primary-key migration later?
2. Should security-adjacent internal rows (`RefreshToken`, `InviteToken`) receive public external IDs at all?
3. Should event-like rows use `evt_` everywhere, or distinguish `out_` for outbox and `gevt_` for gateway events?
4. Should payment-core IDs use shorter prefixes (`pi_`, `pr_`, `rf_`) and admin/internal rows use longer prefixes for readability?
5. What deprecation period is acceptable for UUID route params in dashboard/external API?

## Initial Recommendation

Approve phases 0-6 first:

1. Add the shared prefix registry in `common`.
2. Align VA and rail prefix call sites to the shared registry without data/schema changes.
3. Add gateway ID generation infrastructure.
4. Add external IDs to payment core resources.
5. Expose dual IDs and dual lookup.
6. Move dashboard and SDKs to external IDs.
7. Fix deterministic idempotency-key issues.

Do not migrate physical primary keys until the public/API transition is complete and stable.

## Progress

- Phase 1 complete: added `MasonXIdPrefix` in `common` with existing VA/rail prefixes and planned gateway prefixes; added uniqueness/format tests; `cd backend && mvn -pl common test` passed.
- Phase 2 complete: replaced VA and rail `SnowflakeIdGenerator.generate("...")` prefix literals with `MasonXIdPrefix`; registered extra existing VA/rail prefixes found during implementation (`tx_rail_`, `tx_fund_`, `tx_close_`, `m20_`, `e2e_`, `i20_`); `cd backend && mvn -pl virtual-account-service,rail-service -am test` passed outside sandbox.
- Phase 3 complete: added gateway `SnowflakeIdGenerator` config (`gateway.id.node-id`, `GATEWAY_NODE_ID` default) and `GatewayIdService.generate(MasonXIdPrefix)`; added focused unit tests; `cd backend && mvn -pl gateway-service -am test` passed.
- Phase 4 complete: added gateway migration `V64__add_gateway_external_ids.sql` for nullable `external_id` columns, deterministic backfill, and partial unique indexes across payment intents, payment requests, refunds, outbox events, gateway events, and webhook deliveries; added matching entity fields; `cd backend && mvn -pl gateway-service -am test` passed.
- Phase 5 complete: gateway writers now assign external IDs for new payment intents, payment requests, refunds, outbox events, gateway events, and webhook deliveries; payment, refund, and webhook delivery DTOs expose additive `externalId`; repository/service lookup helpers support external IDs; payment read models carry payment intent `externalId`; `cd backend && mvn -pl gateway-service -am test` passed.
- Phase 6 complete: payment intent API routes and dashboard payment routes accept either UUID or `pi_...` IDs; payment-intent refund subroutes resolve either form; webhook delivery replay accepts either UUID or `whd_...`; targeted tests covered external ID resolution/replay and `cd backend && mvn -pl gateway-service -am test` passed.
- Phase 7 complete: dashboard payments/refunds now display and route with external IDs when present while preserving UUID fallback; refund responses include both refund and parent payment external IDs; server SDK types/docs and dashboard quickstart/demo examples prefer `externalId ?? id`; `cd backend && mvn -pl gateway-service -am test`, `cd dashboard && npm run build`, and `cd sdk/server && npm run build` passed.
- Phase 8 complete: hosted payment-link checkout, subscription first-charge checkout, TEST connector preview, and Stripe redirect preparation now derive provider idempotency keys from stable MasonX resource IDs instead of random UUID suffixes or provider-first creation; direct and delayed 3DS attempt records persist the provider idempotency key; regression tests capture outbound `ChargeRequest` keys; random provider-idempotency scan returned no matches; `cd backend && mvn -pl gateway-service -am test` passed.
- Phase 9 complete: disputes, dispute evidence files, scheduled retry jobs, and webhook endpoints now have additive `externalId` columns, DTO fields, creation-time assignment, and UUID/external-ID route compatibility where dashboard actions address the resource; dashboard disputes, scheduled retries, and webhook endpoints now prefer external IDs for display/actions while preserving UUID fallbacks; `cd backend && mvn -pl gateway-service -am test` and `cd dashboard && npm run build` passed.
- Phase 10 complete: documented UUID route params and UUID response `id` fields as legacy compatibility surface; `externalId` is now the preferred public identifier in API/SDK guidance; no runtime behavior change; `git diff --check` passed.
- Dashboard E2E cleanup complete: merchant settings and billing resources (`mer_`, `cus_`, `sub_`, `inv_`) now expose/display additive `externalId` values while keeping UUID primary keys and UUID-backed dashboard actions for same-version compatibility; `cd backend && mvn -pl gateway-service -am test`, `cd dashboard && npm run build`, and `git diff --check` passed.
- Post-Phase-10 code review found two defects not caught by the phases above (see Changelog): `prepareStripe()`'s idempotency-key fix from Phase 8 was incomplete, and V66/V67's row-number backfill did not produce Snowflake-shaped `external_id` values. Both fixed and verified against the running dev DB; `cd backend && mvn -pl gateway-service -am test` (294 tests) passed.

## Changelog

- 2026-07-01: Phase 8 addendum. Stripe redirect preparation now creates a local MasonX `PaymentIntent` before calling Stripe, sends a Stripe idempotency key derived from that local intent, stores the Stripe provider payment ID/response on the same row, and finalizes that prepared row in `stripe-result` instead of creating a provider-first orphan.
- 2026-07-01: Phase 9. Added external IDs for remaining dashboard operational resources (`dp_`, `def_`, `retry_`, `whe_`), including migration `V66`, backend dual lookup for dispute/retry/webhook endpoint routes, and dashboard updates to display/use external IDs when present.
- 2026-07-01: Phase 10. Documented the same-version ID compatibility policy: keep UUID `id` fields and UUID route aliases for compatibility, but treat them as legacy external-client surface; prefer `externalId` for dashboard routes, SDK calls, logs, and merchant support references.
- 2026-07-01: Dashboard E2E cleanup. Added additive external IDs for merchants and billing resources (`mer_`, `cus_`, `sub_`, `inv_`) and switched the reported dashboard surfaces to display public IDs with UUID fallback.
- 2026-07-01: Code-review fix. `prepareStripe()` still sent Stripe a fresh `UUID.randomUUID()`-derived idempotency key on every call, with no claim guard and no DB state check — contrary to the Phase 8 addendum note above, this redirect-prep call site was never actually made idempotent. Replaced with a deterministic per-attempt key (`pl-{linkId}-stripe-pi-{attemptNumber}`) plus an idempotent DB lookup (`PaymentIntentRepository.findTopByMerchantIdAndIdempotencyKeyStartingWithOrderByCreatedAtDesc` / `countByMerchantIdAndIdempotencyKeyStartingWith`) that resumes an in-flight attempt instead of minting a duplicate Stripe PaymentIntent; concurrent-request races are resolved via the existing `(merchant_id, idempotency_key)` unique constraint. Added `PublicPaymentControllerTest` coverage for the branch that doesn't require mocking the Stripe SDK's static calls (this module pins Mockito to `mock-maker-subclass`, so the Stripe-calling branches remain uncovered by unit tests, same as before this fix).
- 2026-07-01: Backfill ID fix. V66/V67 backfilled pre-existing merchants/customers/subscriptions/invoices/disputes/dispute-evidence-files/scheduled-retry-jobs/webhook-endpoints with small sequential `external_id` suffixes (`mer_1`, `dp_1`, ...) instead of real Snowflake IDs, exposing creation order/count and violating this doc's own backfill requirement to "not expose ordering assumptions stronger than Snowflake provides." Added `V68__fix_merchant_billing_external_id_backfill.sql` and `V69__fix_dashboard_external_id_backfill.sql`, which re-derive only the small-integer artifacts (regex-guarded, so already-correct values are untouched) using the same bit layout as `SnowflakeIdGenerator` under a reserved backfill-only node id (63) that no live service instance can ever be configured with. Verified against the running dev DB: `mer_27` → `mer_860165721152548891`, etc.
