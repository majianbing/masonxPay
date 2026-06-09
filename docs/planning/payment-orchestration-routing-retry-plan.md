# MasonXPay Payment Orchestration, Routing, Retry, and Instrument Plan

Stable architecture extracted from this tracker lives in [routing and orchestration](../architecture/routing-orchestration.md). Keep this file focused on phase status, open decisions, and implementation notes.

This plan reorganizes the Yuno-like orchestration discussion into a staged MasonXPay architecture. The near-term goal is a deterministic payment orchestration engine: route selection, provider fallback, retry control, provider health, and merchant-visible operations. The long-term goal is to leave a clean path toward portable card instruments, network tokens, or a PCI-scoped card vault without forcing MasonXPay to handle PAN now.

## Positioning

MasonXPay should prioritize deterministic orchestration before the AI control plane. AI remains useful later for investigation and recommendations, but runtime payment decisions must continue to be made by explicit configuration, validators, and payment-state services.

The product direction is:

```text
provider connectors
  -> payment instruments and safe metadata
  -> deterministic routing and retry engine
  -> provider health and operational visibility
  -> optional future vault/network-token integrations
  -> advisory AI after the execution model is mature
```

## Current Boundary

MasonXPay currently avoids raw card data. The browser SDK or provider SDK collects sensitive card details, and MasonXPay receives provider-owned tokens or gateway tokens. This keeps the system simpler and avoids expanding PCI scope before the orchestration product is mature.

Current card limitations:

- PSP-owned tokens are not portable across PSPs.
- Card routing cannot rely on full PAN, BIN, issuer, or card type unless safe metadata is supplied by the checkout or provider.
- Cross-PSP card fallback may require the customer to re-enter or re-authorize the card unless a portable vault/network token exists.

This is an acceptable near-term tradeoff.

## Core Design Rule

Do not add raw PAN, CVV, or raw card payloads to `payment_intents`, `payment_tokens`, provider responses, logs, Kafka events, read models, webhooks, or dashboard APIs.

If MasonXPay handles card data in the future, that handling must live behind a small, isolated sensitive-data boundary. The rest of the system should receive only safe metadata and opaque references.

## Instrument Domain

Introduce a payment-instrument domain before adding portable card support. Routing should depend on `PaymentInstrument` and `RoutingContext`, not directly on provider-specific token strings.

Suggested instrument fields:

```text
payment_instruments
- id
- merchant_id
- customer_id
- type: CARD | WALLET | LOCAL_METHOD | BANK_TRANSFER
- source: PROVIDER_TOKEN | WALLET_TOKEN | LOCAL_PAYMENT_HANDLE | VAULT_TOKEN | NETWORK_TOKEN
- portability: PROVIDER_SCOPED | PORTABLE | UNKNOWN
- provider
- provider_account_id
- token_reference
- card_brand
- last4
- expiry_month
- expiry_year
- bin_country
- issuer_country
- card_type
- wallet_type
- created_at
- updated_at
```

`token_reference` must be an opaque reference. For provider-owned tokens, it points to a provider token. For a future vault, it points to a vault token. For a future network-token integration, it points to a network-token reference or token service reference. It must not contain PAN.

## Routing Context

The routing engine should evaluate a normalized context:

```text
RoutingContext
- merchant_id
- mode
- amount
- currency
- country
- payment_method_type
- capture_method
- customer_id
- order_id
- metadata
- instrument_id
- instrument_source
- instrument_portability
- card_brand
- bin_country
- issuer_country
- card_type
- wallet_type
- provider_health
- provider_capabilities
```

Fields can be absent. A condition requiring unavailable metadata should not match unless explicitly configured as `missing`.

## Routing Field Model

Routing conditions should support two field classes:

```text
built-in routing fields
  Code-defined, strongly typed fields used by core payment and provider logic.

custom routing attributes
  Merchant-defined, metadata-backed fields registered through the dashboard.
```

Built-in fields include stable payment and instrument context such as amount, currency, country, payment method type, capture method, card brand, BIN country, issuer country, card type, wallet type, provider health, and provider capabilities. These fields require backend support because the engine must know how to read them, validate them, type-check them, and decide whether they are available before provider selection.

Custom routing attributes let merchants extend routing without code changes when the value comes from bounded payment, customer, or checkout metadata. They must be registered before use:

```text
routing_attributes
- id
- merchant_id
- key
- label
- type: STRING | NUMBER | BOOLEAN | ENUM | COUNTRY | CURRENCY | EMAIL_DOMAIN
- source: PAYMENT_METADATA | CUSTOMER_METADATA | CHECKOUT_CONTEXT
- allowed_operators
- enum_values
- required_before_routing
- pii_classification
- max_value_length
- enabled
- created_at
- updated_at
```

Examples:

```text
metadata.customer_tier = vip
metadata.checkout_channel = mobile
metadata.risk_segment = low
metadata.email_domain = example.com
```

Metadata-backed routing must be bounded:

- enforce a maximum metadata payload size on payment creation
- enforce maximum key count and key length
- enforce maximum value length per registered routing attribute
- reject nested or unregistered metadata fields in routing policies
- validate type coercion before publishing a policy
- avoid full user-defined regex in the first version

Email matching should start with safe derived fields such as `email_domain`, not arbitrary email regex. If regex support is ever added, it must be allowlisted, length-limited, validated at publish time, protected against catastrophic patterns, and audited.

## Provider Capability Matrix

Routing should use provider capabilities instead of assuming every connector can process every method.

Capability examples:

- payment method type: card, wallet, local method, bank transfer
- country and currency support
- manual capture support
- refund and partial refund support
- 3DS / SCA support
- redirect flow support
- provider-token support
- vault-token support
- network-token support
- installment support
- minimum and maximum amount

This becomes especially important for local payment methods and wallets, where provider availability matters more than card metadata.

Capabilities are account-scoped. MasonXPay supports multiple accounts under the same provider brand, so the capability record must attach to a concrete `provider_account_id`, include `merchant_id`, and be evaluated before a route step is considered executable. This supports patterns such as:

```text
Stripe US primary account: card, USD, US, provider-token only
Stripe US backup account: card, USD, US, provider-token only
Mollie EU account: card, wallet, EUR, selected EU countries, redirect support
```

## Routing Model

Replace simple primary/fallback routing with versioned route policies:

```text
RoutePolicy
  RouteSet[]
    ConditionSet[]
    RouteStep[]
```

Recommended behavior:

- Route policies are tenant-scoped and versioned.
- Draft policies can be simulated before publishing.
- One published policy is active per merchant/mode.
- Condition sets are ordered; first match wins.
- Conditions inside a condition set are ANDed.
- Route steps target concrete provider accounts, not only provider brands.
- Provider brand is metadata derived from the provider account.
- Policy publication is audited.

This preserves MasonXPay's existing multi-account model. A route can fallback within one provider brand before crossing to another provider:

```text
Step 1: Stripe US primary account
Step 2: Stripe US backup account
Step 3: Braintree US account
```

Credential portability must still be explicit. A provider-scoped token should be treated as usable only by the exact provider account that created it unless a future capability marks the token as portable across a provider group, vault, or network-token integration.

Condition examples:

- country is `US`
- currency is `USD`
- payment method is `card`
- amount is between `1000` and `50000`
- card brand is `VISA` if metadata is available
- provider health is not degraded
- metadata field equals configured value

## Outcome-Based Fallback

Routing should understand outcomes, not only provider order.

Normalize provider attempt outcomes into internal categories:

```text
APPROVED
REQUIRES_ACTION
HARD_DECLINE
SOFT_DECLINE
INSUFFICIENT_FUNDS
AUTHENTICATION_REQUIRED
RISK_DECLINE
INVALID_PAYMENT_METHOD
PROVIDER_TIMEOUT
PROVIDER_UNAVAILABLE
PROVIDER_ERROR
UNKNOWN_FAILURE
```

Fallback rules should be conservative:

- Retry or fallback on timeout, provider unavailable, and selected transient errors.
- Avoid automatic cross-PSP retry for fraud, stolen card, invalid card, invalid CVV, or clear hard declines.
- Preserve idempotency and record each provider attempt.
- For card payments with provider-scoped tokens, fallback to another PSP is allowed only when the credential is portable or the customer re-enters/re-authorizes the payment method.

## Retry Types

Keep retry mechanisms separate:

```text
technical retry
  Short retry for network/transient failures inside one provider call boundary.

route fallback
  Same payment intent tries another route step after a normalized outcome allows fallback.

scheduled recovery retry
  Delayed retries for capture, refund, recurring/subscription, or recoverable asynchronous operations.
```

Do not mix these into one generic retry loop. Each has different risk, idempotency, and customer-experience requirements.

## Tokenization Upgrade Path

There are three relevant token categories:

```text
Provider token
  PSP-owned token such as Stripe payment method or Braintree nonce.
  Usually not portable.

Vault token
  PSP-neutral third-party vault reference.
  Portable only if the vault can prepare credentials for multiple PSPs.

Network token
  Scheme token from Visa, Mastercard, or a token requestor.
  Potentially portable, but requires provider and network support.
```

Recommended path:

1. Keep provider-owned tokenization now.
2. Add `PaymentInstrument` and safe metadata.
3. Add provider capability checks and route simulation.
4. Add optional third-party vault integration when cross-PSP card portability becomes a real requirement.
5. Treat direct network-token or MasonXPay-owned card vaulting as later enterprise infrastructure, not near-term product work.

## Future PAN Handling

If MasonXPay eventually handles PAN, it should process PAN transiently and tokenize immediately. CVV must not be stored after authorization. PAN handling should be isolated in a dedicated card intake/vault service with strict PCI DSS controls.

Future PCI-scoped flow:

```text
card intake boundary
  -> tokenize or vault immediately
  -> return instrument token and safe metadata
  -> routing engine chooses PSP
  -> provider adapter charges through vault/network/provider credential
```

The payment core should still operate on opaque instrument references and safe metadata only.

## Local Payment Methods And Wallets

Local payment methods and wallets can support strong orchestration without PAN:

- route by country, currency, amount, and method availability
- route by provider capability
- fallback when a provider is unavailable before payment instructions are created
- show provider health and settlement/cost tradeoffs
- keep method-specific handles provider-scoped unless a portable handle exists

For local payment methods that create provider-specific instructions, fallback after instruction creation may require starting a new payment attempt and clearly communicating that to the customer.

## Implementation Tracker

Status legend:

- `[x]` Done and committed.
- `[~]` Partially implemented; usable foundation exists, but listed remaining work is still open.
- `[ ]` Not started.

Last checkpoint commit: `a71e89b Fix simulator payment link checkout flow`.
Committed checkpoint: provider-scoped instruments are created during hosted-checkout tokenization, linked to `payment_tokens`, live confirm uses instrument portability to decide whether route fallback is allowed, provider payment-method references are redacted from request logs, newly created connector accounts get default card capability rows, connector-scoped capability management APIs and dashboard UI are available, outcome-action retry/next/stop behavior has focused tests, Mockito tests are configured to avoid JDK self-attach, and `AGENTS.md` / `CLAUDE.md` / `SECURITY.md` now point future sessions at the Phase O boundary and status. Payment-link hosted-checkout behavior with capabilities enabled was verified manually in Docker using the TEST-mode Mason Simulator flow.

Verification:

- [x] Payment-link hosted-checkout behavior with connector capabilities enabled was verified in Docker using the TEST-mode Mason Simulator provider, so validation does not depend on outside PSP services.

### O1: Instrument And Context Foundation `[x]`

Done:

- [x] Added `PaymentInstrument` model, repository, safe card/wallet metadata fields, source, and portability flags.
- [x] Added `RoutingContext`.
- [x] Kept existing provider-token flow working.
- [x] Marked the architecture boundary that provider-owned tokens are `PROVIDER_SCOPED`.
- [x] Persisted `PaymentInstrument` rows from hosted checkout tokenization.
- [x] Linked `PaymentToken` / `gw_tok_*` consumption to the persisted instrument.
- [x] Confirm validates provider-scoped instruments remain pinned to the original connector account.
- [x] Live confirm uses instrument portability to decide whether route fallback is allowed.
- [x] Redact provider payment-method references such as `providerPmId`, `paymentMethodId`, `tokenReference`, and `providerToken` from stored API request/response logs.

### O2: Capability-Aware Routing `[x]`

Done:

- [x] Added `ProviderAccountCapability` matrix for account-scoped method/country/currency/capture/token support.
- [x] `RoutingEngine.resolvePlan` filters active route-policy steps by tenant, mode, account status, health, cost, and declared capabilities.
- [x] Added `POST /api/v1/merchants/{merchantId}/route-simulations` dry-run route resolution without calling a PSP.
- [x] TEST-mode `SIMULATOR` connectors can be used for local connector preview and route-policy experiments without external services.
- [x] Newly created connector accounts seed a default `card` capability row so route policies have concrete capability data for simulator and common providers.
- [x] Added connector-scoped capability management APIs: list and replace under `/api/v1/merchants/{merchantId}/connectors/{accountId}/capabilities`.
- [x] Added dashboard UI for editing connector-scoped provider capabilities from the Connectors page.
- [x] Added hosted checkout support for simulator payment-link testing and capability-aware account selection during payment-link tokenization.
- [x] Verified payment-link hosted-checkout end-to-end behavior in Docker with capability-aware routing enabled.

### O3: Route Policy V2 `[x]`

Done:

- [x] Added route policy, route, and step tables/entities/repositories.
- [x] Active policies resolve before legacy `routing_rules`; legacy routing remains the fallback path.
- [x] Basic condition matching supports built-in fields, simple metadata equality, `in`, missing checks, and numeric comparisons.
- [x] Added draft create/replace, publish, archive, list, and detail APIs under `/api/v1/merchants/{merchantId}/route-policies`.
- [x] Publish validates route shape and active provider-account ownership before activation and archives the previous active policy for the same merchant/mode.
- [x] Added audit history for publish/archive changes under `/api/v1/merchants/{merchantId}/route-policies/{policyId}/audit-logs`.
- [x] Added strict publish-time condition-schema validation against registered `metadata.*` routing attributes and supported built-in fields.
- [x] Added dashboard UI for policy list, dedicated create/edit pages, publish/archive, audit review, and dry-run route simulation under `/routing/policies`.

### O3b: Routing UI Consolidation `[x]`

Done:

- [x] Dashboard navigation now exposes one Routing entry backed by route policies; the legacy routing rules page is no longer in normal navigation.
- [x] `/routing/policies` is now a policy list with mode/status filtering and publish/archive actions.
- [x] `/routing/policies/new` is a dedicated create page.
- [x] `/routing/policies/{policyId}` is a dedicated view/edit page with audit history and dry-run simulation.
- [x] Legacy backend APIs and runtime fallback remain for compatibility, but no old-rule migration path is required for this phase.

### O4: Outcome-Based Fallback `[x]`

Done:

- [x] Route steps carry `outcome_actions_json`; active policy resolution attaches those actions to route candidates.
- [x] `PaymentRetryOrchestratorService` maps provider failures into conservative outcome categories and supports `retry`, `next`/`fallback`, and `stop`/`fail` actions.
- [x] Existing default behavior is preserved when no outcome action is configured: retryable provider errors retry/fallback according to `PaymentRetryContext`; hard declines stop.
- [x] `simulator_declined` is treated as a hard decline so offline simulator tests do not accidentally model cross-connector recovery for a card decline.
- [x] Wire persisted instrument portability into live confirm.
- [x] Allow live route fallback only for portable instruments; provider-scoped gateway tokens remain single-connector.
- [x] Added focused tests for route-step outcome actions including `next` and `stop`.

### O5: Scheduled Retry Orchestration `[x]`

Boundary:

- This phase covers delayed recovery for safe asynchronous operations: manual capture and refund.
- It is not a payment-success optimization feature for customer-present checkout failures, lower-cost reattempts, or cross-provider card retries.
- Subscription/recurring billing now has its own invoice retry/dunning worker. Phase O scheduled retry remains scoped to capture/refund recovery and should not absorb billing retry or customer-present checkout retry.
- Funds-safety boundary: MasonXPay must not deduct merchant funds through automatic background retries without explicit approval. Failed refunds are marked for review by default; automatic refund retry is disabled unless a future provider-specific, idempotency-safe, merchant-approved policy enables it.

Done:

- [x] Added `scheduled_retry_jobs` with merchant scope, operation type, schedule, max attempts, status, lock/completion metadata, and capture/refund target references.
- [x] Added backend entity/repository/service support for scheduling, listing, canceling, and finding due delayed recovery retries.
- [x] Added merchant-scoped status visibility API under `/api/v1/merchants/{merchantId}/scheduled-retries`.
- [x] Added scheduled due-job worker that claims jobs, performs capture/refund recovery outside DB transactions, records success/final failure atomically with payment/refund state and outbox events, and reschedules retryable failures with bounded attempts.
- [x] Failed manual capture attempts now keep the payment in `REQUIRES_CAPTURE` and schedule delayed recovery instead of marking the payment terminal immediately.
- [x] Failed refund attempts now keep the refund `PENDING` for review by default. Automatic refund retry is feature-flagged off because duplicate refund retries can cause merchant fund loss.
- [x] Added dashboard scheduled-retry visibility and cancellation under `/scheduled-retries`; by default this is automatic for capture recovery, not refund money movement.
- [x] Kept scheduled recovery separate from immediate provider technical retries and route fallback; no customer-present card confirm retries are automatically scheduled in this increment.

Follow-up hardening:

- [ ] Add provider-specific retryability codes for capture/refund outcomes when providers expose enough detail.
- [ ] Add an explicit approval workflow before enabling any refund retry that can move funds.
- [ ] Extend the same scheduled-recovery pattern to subscription/recurring operations when that product module exists.

### Subscription and Recurring Billing

Subscription and recurring billing has moved to standalone Phase S because it owns customer, payment-method, subscription, invoice, off-session execution, and dunning boundaries. Phase S now provides its own invoice retry/dunning worker; Phase O scheduled retry remains focused on payment capture/refund recovery.

See [subscription and recurring billing plan](subscription-recurring-billing-plan.md).

### O6: Optional Portable Card Support `[ ]`

Remaining:

- [ ] Integrate a third-party vault/tokenization provider if needed.
- [ ] Add support for portable vault tokens behind `PaymentInstrument`.
- [ ] Add network-token capability flags without assuming direct Visa/Mastercard access.

### AI Later: Advisory Control Plane `[ ]`

Remaining:

- [ ] Analyze provider health, approval rate, latency, cost, and fallback outcomes.
- [ ] Recommend route-policy changes.
- [ ] Draft policy diffs.
- [ ] Require deterministic validation and human approval before publication.
