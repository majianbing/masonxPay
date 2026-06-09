# MasonXPay Subscription and Recurring Billing Plan

Stable architecture extracted from this tracker lives in [subscriptions and billing](../architecture/subscriptions-billing.md). Keep this file focused on phase status, open decisions, and implementation notes.

This plan defines a separate product phase for subscription and recurring billing. It is intentionally separate from Phase O routing/retry work: subscriptions create invoices and off-session payment obligations; routing decides how eligible payment attempts execute; billing owns invoice retry/dunning instead of reusing customer-present checkout retry.

## Current Boundary

MasonXPay now supports the core subscription and recurring billing workflow: merchant-scoped customers, reusable payment-method references, subscriptions, checkout links, invoices, off-session invoice payment execution, recurring retry/dunning, and dashboard operations.

Systems this phase reuses:

- `PaymentInstrument`: opaque payment reference with source and portability metadata.
- `PaymentIntent`: authoritative payment execution record.
- `RoutingEngine`: deterministic provider/account selection.
- `scheduled_retry_jobs`: delayed recovery infrastructure, used for capture/refund recovery. Billing has its own invoice retry/dunning worker and does not rely on customer-present checkout retries.
- Outbox/webhook infrastructure for lifecycle events.
- Mason Simulator for local/offline test flows.

Implemented foundation:

- customer records
- customer default payment methods
- reusable PSP payment-method setup for Stripe, Square, Braintree, and Mason Simulator
- Mollie recurring customer creation and first-payment boundary, with mandate completion still pending
- subscription plans/items
- public subscription checkout links
- invoices and invoice payment-attempt storage
- off-session invoice payment execution
- recurring retry and dunning worker
- dashboard customer, subscription, checkout-link, and invoice operations

Remaining gaps:

- merchant-configurable dunning/retry policy UI
- customer notification workflows
- Mollie mandate completion and recurring charge support
- promotions/coupons

Current E2E checkpoints:

- [x] Non-trial subscription first payment activates through Mason Simulator and appears in Payments.
- [x] Trial subscription activation stores a default method without creating a payment.
- [x] Checkout link reuse is rejected and does not create duplicate payments or methods.
- [x] Failed first payment does not activate the subscription and does not leave unsafe default-method state.
- [x] Current-period invoice generation is idempotent through the dashboard/API flow.

## Billing Ownership Decision

MasonXPay should own subscription plans, billing schedules, invoices, and retry/dunning state. PSP subscription products such as Stripe Billing should be treated as optional future connector-specific adapters, not the core model.

Recommended default:

```text
BILLING_OWNER = MASONXPAY
```

Provider responsibilities:

- run customer-present setup or first-payment flows that create reusable payment credentials where supported
- return safe reusable PSP references such as provider customer IDs, payment method IDs, card-on-file IDs, vaulted tokens, or mandate IDs
- execute off-session payment attempts
- return payment, refund, capture, cancel, and sync outcomes
- emit provider webhooks that MasonXPay reconciles into its own state

MasonXPay responsibilities:

- customer records
- subscription lifecycle
- invoice generation and invoice lifecycle
- recurring retry policy and dunning state
- merchant dashboard operations
- routing each invoice payment attempt through deterministic route/capability rules

Provider-owned billing can be added later as:

```text
BILLING_OWNER = PROVIDER
```

That mode should be explicit because it limits MasonXPay routing, fallback, invoice lifecycle control, and cross-provider portability.

## Current PSP Reusable Method Boundary

The current S1/S2 foundation proves the subscription/customer model, a TEST-mode Mason Simulator activation path, and an explicit reusable-method provider capability. Subscription checkout now converts a customer-present provider token/nonce into a provider-scoped `PaymentInstrument` with:

- `source = VAULT_TOKEN`
- `provider_account_id`
- `provider_customer_reference`
- `token_reference` containing only the safe reusable PSP reference

```text
customer opens subscription checkout
  -> MasonXPay creates or reuses a PSP customer where supported
  -> SDK/UI collects customer-present authorization
  -> PSP confirms a reusable method, vault token, card-on-file ID, or mandate
  -> MasonXPay stores only the safe provider reference on PaymentInstrument
  -> later invoices charge that saved reference off-session
```

Current provider coverage:

- Stripe: creates/reuses a `Customer`, attaches a Stripe `PaymentMethod`, and charges with the customer reference plus `setup_future_usage=off_session`.
- Square: creates/reuses a Square Customer, stores a card-on-file through the Cards API, and charges with `customer_id`.
- Braintree: creates a Vault customer from a Drop-in nonce and stores the vaulted payment method token.
- Mason Simulator: returns fake reusable customer/method references for local integration and E2E tests.
- Mollie: creates/reuses a Mollie Customer and marks that a hosted `sequenceType=first` payment flow is required. Mandate confirmation and later `sequenceType=recurring` charges are still pending.

This boundary is intentionally separate from `PaymentProviderService.brand()` and ordinary one-time charge execution. Provider calls remain outside transaction-scoped state updates.

## Non-Negotiable Boundaries

- Every new table must include `merchant_id`.
- Raw PAN, CVV, private keys, provider credentials, webhook signatures, and unredacted PII must not enter logs, Kafka events, read models, dashboard payloads, or AI workflows.
- Subscription billing must not bypass the existing payment state model. Off-session charges should create normal payment intents and payment requests.
- Provider-scoped instruments can only be charged through the original provider account unless a future portable vault/network token explicitly supports broader routing.
- Recurring retry is not customer-present checkout retry and not refund retry. It applies only to invoices generated by an active subscription schedule.
- Automatic recurring retries must be controlled by merchant policy and bounded by attempt count, schedule, and terminal actions.
- Refund auto-retry remains disabled by default. Subscription retry must not become a generic background money-movement mechanism.

## Modular Design

Keep billing as a focused module under a dedicated boundary, for example:

```text
billing/customer
  Owns customer records and customer-level metadata.

billing/paymentmethod
  Owns customer default payment-method references to PaymentInstrument.

billing/subscription
  Owns subscription state, periods, items, cancellation, and lifecycle rules.

billing/invoice
  Owns invoices, invoice status transitions, totals, and invoice payment attempts.

billing/worker
  Owns due invoice generation and off-session invoice payment execution.

billing/dunning
  Owns retry policy, failed invoice retry schedule, customer notification hooks, and final dunning actions.

billing/web
  Owns merchant APIs and dashboard-facing DTOs.
```

Cross-module calls should go through services or events. Billing should not shortcut into provider adapters; it should call payment services using normal payment intent and instrument abstractions.

## Stage S0: Architecture and State Model `[x]`

Goal: lock the domain model before writing runtime code. The runtime model now exists through S1-S5; remaining work here is documentation polish, not a blocker for the implemented billing foundation.

Deliverables:

- [ ] Add lifecycle diagrams for customers, subscriptions, invoices, and invoice payment attempts.
- [x] Define status enums and legal state transitions.
- [x] Define tenant and RBAC permissions for billing resources.
- [x] Define webhook event names and payload boundaries.
- [x] Define how recurring billing uses `PaymentInstrument` without expanding PCI scope.

Suggested status models:

```text
SubscriptionStatus
  INCOMPLETE
  TRIALING
  ACTIVE
  PAST_DUE
  CANCELED
  UNPAID

InvoiceStatus
  DRAFT
  OPEN
  PAID
  VOID
  UNCOLLECTIBLE

InvoicePaymentAttemptStatus
  PENDING
  SUCCEEDED
  FAILED
```

## Stage S1: Customer and Payment Method Foundation `[x]`

Goal: create reusable customer records and link them to safe payment instruments.

Current progress:

- [x] Added merchant-scoped customer and customer payment-method storage.
- [x] Added backend APIs for customer create/list/get/update and payment-method attach/detach.
- [x] Enforced payment-instrument ownership when attaching a reusable method to a customer.
- [x] Added focused service tests for tenant/customer boundaries.
- [x] Added dashboard customer list/create/edit and basic payment-method attach/visibility.
- [x] Added BillingCustomerController integration tests: tenant isolation, mode filtering, cross-merchant instrument rejection, validation.

Tables:

```text
customers
- id
- merchant_id
- email
- name
- metadata_json
- created_at
- updated_at

customer_payment_methods
- id
- merchant_id
- customer_id
- payment_instrument_id
- status
- is_default
- created_at
- updated_at
```

Rules:

- `customer_payment_methods.payment_instrument_id` points to `PaymentInstrument`.
- Do not store raw provider payment method payloads.
- A provider-scoped instrument can only be charged through the provider account that created it.
- A default payment method is merchant/customer-scoped, never global.

Tests:

- [x] Tenant scoping for customer reads/writes.
- [x] Only owned instruments can attach to owned customers.
- [x] Default payment method uniqueness per customer.

## Stage S2: Subscription, Checkout Link, and Invoice Foundation `[x]`

Goal: create subscriptions, support merchant-generated subscription checkout links, activate subscription checkout in TEST mode, and prepare invoice generation.

Current progress:

- [x] Added merchant-scoped subscription, subscription item, and subscription checkout-link storage.
- [x] Added trial-aware subscription creation. `trialDays > 0` starts the subscription as `TRIALING`; otherwise it starts as `INCOMPLETE` until checkout/payment activation exists.
- [x] Added backend APIs to create/list/get subscriptions and create/list subscription checkout links.
- [x] Added TEST/LIVE mode on subscriptions so public checkout links can use the correct connector path later.
- [x] Added public checkout-link lookup for `/subscribe/{token}` previews.
- [x] Added TEST-mode subscription activation through Mason Simulator from the public checkout page.
- [x] Added backend checkout activation that stores the payment method, charges non-trial first periods, and keeps free-trial activation from moving funds.
- [x] Added focused service tests for customer ownership, trial creation, item persistence, and checkout-link URL generation.
- [x] Added focused service tests that verify trial activation stores a default payment method without provider charge execution.
- [x] Added controller integration coverage for public subscription checkout lookup.
- [x] Verified the minimum local stack with `docker compose up --build` after the Flyway V53/V54 migration fix.
- [x] Added invoice and invoice-payment-attempt storage.
- [x] Added merchant-scoped APIs to list invoices and idempotently generate the current-period invoice.
- [x] Added focused service tests for current-period invoice generation and invalid subscription state guards.
- [x] Added customer TEST/LIVE mode isolation so subscription customer selectors and customer APIs do not mix environments.
- [x] Added explicit reusable payment-method provider capability and persisted PSP customer references on `PaymentInstrument`.
- [x] Added provider setup paths for Stripe, Square, Braintree, and Mason Simulator.
- [~] Added Mollie recurring customer/first-payment boundary; mandate completion still requires hosted first-payment/webhook handling.
- [ ] Add reusable promotion/coupon entities — deferred until after S3 off-session payment is validated.
- [x] Add public `/subscribe/{token}` checkout APIs/pages and first-payment activation foundation.
- [x] Added automated period advancement worker: advances ACTIVE subscription periods, generates invoices atomically, honors cancel_at_period_end, idempotent on retry.
- [x] Added SubscriptionController integration tests: tenant isolation, validation guards, checkout link state checks.
- [x] Fixed invoice and invoice_payment_attempt missing mode column (V58 migration).
- [x] Added mode to invoice response DTO.

Tables:

```text
subscriptions
- id
- merchant_id
- customer_id
- mode
- status
- currency
- interval_unit
- interval_count
- current_period_start
- current_period_end
- trial_ends_at
- cancel_at_period_end
- canceled_at
- metadata_json
- created_at
- updated_at

subscription_items
- id
- merchant_id
- subscription_id
- description
- amount
- quantity
- created_at
- updated_at

subscription_checkout_links
- id
- merchant_id
- customer_id
- subscription_id
- token
- status
- expires_at
- completed_at
- created_at
- updated_at

promotions
- id
- merchant_id
- name
- type
- duration_type
- trial_days
- percent_off
- amount_off
- currency
- cycle_count
- starts_at
- ends_at
- max_redemptions
- status
- metadata_json

subscription_promotions
- id
- merchant_id
- subscription_id
- promotion_id
- applied_at
- starts_at
- ends_at
- remaining_cycles
- status

invoices
- id
- merchant_id
- customer_id
- subscription_id
- status
- amount_due
- amount_paid
- currency
- period_start
- period_end
- due_at
- next_payment_attempt_at
- created_at
- updated_at

invoice_payment_attempts
- id
- merchant_id
- invoice_id
- payment_intent_id
- attempt_number
- status
- failure_code
- failure_message
- created_at
- updated_at
```

Rules:

- Invoice totals come from subscription items at invoice creation time.
- Invoice state is authoritative for billing collection state; payment intents remain authoritative for payment state.
- Invoice creation must be idempotent for a subscription period.
- Free-trial support is the first promotion slice. A full coupon engine should be reusable, not embedded in subscription controller fields.
- Promotions can change invoice amount or billing schedule, but must not bypass subscription, invoice, or payment state transitions.
- A subscription checkout link authorizes/collects the first customer-present payment method before recurring off-session billing is allowed.
- Free-trial activation stores a default payment method and marks the checkout link used without charging funds.
- Non-trial activation charges the first period through the normal payment provider dispatcher before marking the subscription active.

Tests:

- [x] Create trial subscription for a valid customer.
- [x] Create a shareable checkout link for a merchant-owned subscription.
- [x] Public checkout lookup returns customer, merchant, subscription, trial, and item terms.
- [x] Trial checkout activation stores default payment method without charging the provider.
- [x] Subscription checkout stores a reusable provider-scoped payment reference instead of the short-lived customer-present token.
- [x] Docker Compose build/start validates migrations and service health.
- [x] Create subscription with valid customer (INCOMPLETE) and with trial (TRIALING).
- [x] Generate one invoice per subscription period idempotently.
- [x] State transition guards reject invalid changes (CANCELED/UNPAID rejects checkout link creation).

## Stage S3: Off-Session Invoice Payment Execution `[x]`

Goal: charge an open invoice through the normal payment stack.

Flow:

```text
OPEN invoice
  -> load customer default PaymentInstrument (source=VAULT_TOKEN, providerCustomerReference present)
  -> verify connector account mode matches invoice mode
  -> create PaymentIntent (status=PROCESSING) in transaction A
  -> charge off-session via PaymentProviderDispatcher (outside transaction)
  -> requiresAction → treated as failure (customer not present for 3DS)
  -> write InvoicePaymentAttempt, update intent, invoice, subscription, outbox in transaction B
  -> success: invoice PAID, subscription ACTIVE
  -> failure: invoice OPEN, nextPaymentAttemptAt=now, subscription PAST_DUE
```

Current progress:

- [x] Added InvoicePaymentService with two-phase transaction discipline (provider call outside any transaction).
- [x] Added InvoiceController: POST /pay, GET /{id}, GET / (flat, mode-filtered).
- [x] Added CustomerPaymentMethodRepository default method query.
- [x] Added InvoiceRepository flat list query (findByMerchantIdAndMode).
- [x] Added InvoicePaymentResponse DTO.
- [x] Idempotent: already-PAID invoice returns immediately without charging.
- [x] requiresAction treated as failure with requires_customer_action code.
- [x] attempt_number increments per call; attempt linked to payment intent.
- [x] 8 unit tests and 6 controller integration tests.

Rules:

- Off-session execution must not call provider adapters directly.
- Instrument must have source=VAULT_TOKEN and providerCustomerReference set.
- No cross-provider fallback — instrument's providerAccountId used directly, not the routing engine.
- Connector account mode must match invoice mode.
- requiresAction (3DS redirect) is treated as failure for off-session charges.

Tests:

- [x] Provider setup/first-payment flow stores a safe reusable payment reference for Stripe, Square, Braintree, and Mason Simulator.
- [ ] Mollie first-payment mandate confirmation stores the mandate/reference after webhook reconciliation.
- [x] Invoice payment succeeds through simulator and marks invoice paid.
- [x] Failed payment marks subscription past due.
- [x] Provider-scoped instrument stays on its owning connector account (verified via connector account ID).
- [x] Payment intent and invoice attempt are linked.

## Stage S4: Recurring Retry and Dunning `[x]`

Goal: automatically retry failed invoice payments and apply dunning actions.

Implementation: `InvoiceBillingWorker` — a scan-based scheduled worker (not job-queue).

```text
InvoiceBillingWorker (@Scheduled every 5 minutes)
  SELECT invoices WHERE status=OPEN AND nextPaymentAttemptAt <= now
  Optimistic claim per invoice (UPDATE WHERE timestamp matches)
  → InvoicePaymentService.pay() with deterministic idempotency key
  → success: invoice PAID, subscription ACTIVE
  → soft failure (insufficient_funds etc.): nextPaymentAttemptAt += delay
  → hard decline / max attempts: invoice UNCOLLECTIBLE, outbox event
```

Default policy: 3 attempts, delays 3 days / 5 days / 7 days, UNCOLLECTIBLE final action.
Merchant-configurable policy UI deferred.

Idempotency:
- Optimistic claim: concurrent workers race on UPDATE; only one proceeds.
- Claim window: crash leaves invoice unclaimed after 1 hour.
- Deterministic provider key: `"inv-{id}-attempt-{n}"` prevents double-charge.
- Invoice PAID guard in InvoicePaymentService: already-paid invoices are no-ops.

ShardingSphere note: all billing tables must be registered in
`DataSourceConfig.singleTables()` — ShardingSphere intercepts all JDBC and
fails on Postgres-specific syntax for unregistered tables.

Tests:

- [x] Success marks invoice paid and subscription active.
- [x] Retryable failure reschedules with payload delay.
- [x] Hard decline terminates without reschedule.
- [x] Max attempts exhausted applies UNCOLLECTIBLE final action.
- [x] Concurrent claim skip (claimForBilling returns 0).
- [x] Disabled worker does nothing.

## Stage S5: Dashboard and Merchant Operations `[x]`

Goal: give merchants enough visibility and control to operate recurring billing.

Current progress:

- [x] Added dashboard customer management (list, create, edit).
- [x] Added subscription list/detail page with paginated list and status badges.
- [x] Added create subscription dialog with customer selection, recurring amount, interval, and free-trial days.
- [x] Added checkout-link creation — enforces at-most-one-active-link per subscription; disabled for CANCELED/UNPAID.
- [x] Added public `/subscribe/{token}` checkout page with real PSP support (Stripe Elements, Square Web Payments, Braintree Drop-in, Simulator). PSP SDK loads lazily; optimistic SDK cleanup prevents duplicate mount on provider switch.
- [x] Added subscription cancel action with Dialog confirmation (not browser confirm()).
- [x] Added invoice list in subscription detail: period, amount, status badge, Pay button, Write off button, link to payment intent.
- [x] Added standalone Invoices page in sidebar: cross-subscription invoice list with status filter, Pay/Write off/View payment actions.
- [x] Added invoice-to-payment link: `latestPaymentIntentId` in InvoiceResponse, links to /payments/{id}.
- [x] Added mark-uncollectible endpoint and Write off button (stops billing worker retries).
- [x] Added human-readable payment method display: card brand + last4 + expiry + provider label instead of truncated UUID.
- [x] Added Set default and Detach buttons on customer payment methods.
- [x] Added Billing sidebar group (Customers / Subscriptions / Invoices) replacing three flat entries. Auto-expands on any billing route.
- [x] Fixed checkout link UX bug: creating a new link deactivates existing ACTIVE links for the same subscription.

Deliberately deferred:
- Dunning/retry policy configuration UI — billing worker uses sensible defaults (3 attempts, 3/5/7-day delays, UNCOLLECTIBLE final action). Config surface deferred.
- Mollie mandate/first-payment webhook reconciliation.
- Promotions/coupons.

Dashboard pages:

- [x] Customers (list, create, edit, payment method management)
- [x] Subscriptions (list with pagination, detail, checkout links, invoices, cancel)
- [x] Invoices (standalone cross-subscription list)
- [x] Public `/subscribe/{token}` hosted checkout (Stripe, Square, Braintree, Simulator)
- [ ] Dunning/retry policy settings (deferred)

Controls:

- [x] Create/cancel subscription.
- [x] Change default payment method.
- [x] Retry invoice payment manually (Pay button).
- [x] Mark invoice uncollectible (Write off).
- Cancel scheduled invoice retry — not applicable (billing worker is scan-based; Write off is the equivalent).

## API Shape

Initial merchant APIs:

```text
GET    /api/v1/merchants/{merchantId}/customers
POST   /api/v1/merchants/{merchantId}/customers
GET    /api/v1/merchants/{merchantId}/customers/{customerId}
POST   /api/v1/merchants/{merchantId}/customers/{customerId}/payment-methods

GET    /api/v1/merchants/{merchantId}/subscriptions
POST   /api/v1/merchants/{merchantId}/subscriptions
GET    /api/v1/merchants/{merchantId}/subscriptions/{subscriptionId}
GET    /api/v1/merchants/{merchantId}/subscriptions/{subscriptionId}/checkout-links
POST   /api/v1/merchants/{merchantId}/subscriptions/{subscriptionId}/checkout-links
POST   /api/v1/merchants/{merchantId}/subscriptions/{subscriptionId}/cancel

GET    /api/v1/merchants/{merchantId}/invoices
GET    /api/v1/merchants/{merchantId}/invoices/{invoiceId}
POST   /api/v1/merchants/{merchantId}/invoices/{invoiceId}/pay
POST   /api/v1/merchants/{merchantId}/invoices/{invoiceId}/void
POST   /api/v1/merchants/{merchantId}/invoices/{invoiceId}/mark-uncollectible
```

## Recommended Next Implementation Slice

The core S1-S5 workflow is implemented. Continue with focused hardening rather than another broad billing expansion:

- [ ] Add merchant-configurable dunning/retry policy settings.
- [ ] Complete Mollie mandate confirmation and recurring charge support.
- [ ] Add customer notification hooks for failed invoices, upcoming renewals, and final dunning outcomes.
- [ ] Add promotions/coupons after invoice totals and proration rules are explicitly modeled.
- [ ] Expand billing failure-mode tests around worker restarts, provider timeouts, and concurrent invoice payment attempts.

Keep recurring retry scoped to invoices generated by active subscriptions. Do not merge it with customer-present checkout retry, capture recovery, or refund retry.
