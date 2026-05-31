# MasonXPay Subscription and Recurring Billing Plan

This plan defines a separate product phase for subscription and recurring billing. It is intentionally separate from Phase O routing/retry work: subscriptions create invoices and off-session payment obligations; routing decides how eligible payment attempts execute; scheduled retries recover approved recurring invoice attempts only after the subscription domain exists.

## Current Boundary

MasonXPay does not currently support subscription or recurring billing.

Existing systems that this phase can reuse:

- `PaymentInstrument`: opaque payment reference with source and portability metadata.
- `PaymentIntent`: authoritative payment execution record.
- `RoutingEngine`: deterministic provider/account selection.
- `scheduled_retry_jobs`: delayed recovery infrastructure, currently used for capture recovery.
- Outbox/webhook infrastructure for lifecycle events.
- Mason Simulator for local/offline test flows.

Systems that do not exist yet:

- recurring invoice payment attempts
- dunning and customer notification workflows

Systems that now have a foundation but are not complete:

- customer records
- customer default payment methods
- reusable PSP payment-method setup for Stripe, Square, Braintree, and Mason Simulator
- Mollie recurring customer creation and first-payment boundary, with mandate completion still pending
- subscription plans/items
- public subscription checkout links
- invoices and invoice payment-attempt storage

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

## Stage S0: Architecture and State Model `[ ]`

Goal: lock the domain model before writing runtime code.

Deliverables:

- [ ] Add lifecycle diagrams for customers, subscriptions, invoices, and invoice payment attempts.
- [ ] Define status enums and legal state transitions.
- [ ] Define tenant and RBAC permissions for billing resources.
- [ ] Define webhook event names and payload boundaries.
- [ ] Define how recurring billing uses `PaymentInstrument` without expanding PCI scope.

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

## Stage S3: Off-Session Invoice Payment Execution `[ ]`

Goal: charge an open invoice through the normal payment stack.

Flow:

```text
OPEN invoice
  -> load customer default PaymentInstrument
  -> create PaymentIntent with customer_id and invoice metadata
  -> confirm off-session through existing route/payment services
  -> write invoice_payment_attempt
  -> success: invoice PAID, subscription ACTIVE
  -> failure: invoice OPEN, subscription PAST_DUE
```

Rules:

- Off-session execution must not call provider adapters directly.
- Use Mason Simulator for local integration tests.
- Reuse routing capability checks.
- Require a valid reusable PSP reference before any real off-session charge.
- Customer-present setup or first-payment authorization must happen before recurring monthly deductions.
- Do not cross-provider fallback for provider-scoped instruments unless the instrument is portable.

Tests:

- [x] Provider setup/first-payment flow stores a safe reusable payment reference for Stripe, Square, Braintree, and Mason Simulator.
- [ ] Mollie first-payment mandate confirmation stores the mandate/reference after webhook reconciliation.
- [ ] Invoice payment succeeds through simulator and marks invoice paid.
- [ ] Failed payment marks subscription past due.
- [ ] Provider-scoped instrument stays on its owning connector account.
- [ ] Payment intent and invoice attempt are linked.

## Stage S4: Recurring Retry and Dunning `[ ]`

Goal: add retry policy for failed subscription invoices only.

Suggested table:

```text
subscription_retry_policies
- id
- merchant_id
- name
- max_attempts
- retry_delays_json
- final_action
- customer_notifications_enabled
- enabled
- created_at
- updated_at
```

Retry operation:

```text
scheduled_retry_jobs.operation = INVOICE_PAYMENT
```

Rules:

- Retry jobs can only target open invoices generated from subscriptions.
- Retry attempts must create new `invoice_payment_attempts`.
- Hard declines should stop or require customer payment method update, not blind retry.
- Final action can mark invoice `UNCOLLECTIBLE`, keep subscription `PAST_DUE`, or cancel subscription depending on merchant policy.
- Customer notification hooks should be modeled before production use.

Tests:

- [ ] Retry schedule creation from failed invoice payment.
- [ ] Successful retry marks invoice paid and subscription active.
- [ ] Exhausted retries apply final action.
- [ ] Hard declines do not loop indefinitely.

## Stage S5: Dashboard and Merchant Operations `[ ]`

Goal: give merchants enough visibility and control to operate recurring billing.

Current progress:

- [x] Added dashboard customer management.
- [x] Added subscription list/detail page.
- [x] Added create subscription dialog with customer selection, recurring amount, interval, and free-trial days.
- [x] Added checkout-link creation and copy action for merchant testing.
- [x] Added public `/subscribe/{token}` preview page.
- [ ] Add public checkout payment-method authorization and subscription activation before marking the subscription workflow end-to-end complete.
- [ ] Add invoice, dunning/retry policy, and failed invoice retry queue views.

Dashboard pages:

- Customers
- Customer detail with payment methods
- Subscriptions list/detail
- Invoices list/detail
- Dunning/retry policy settings
- Failed invoice retry queue

Controls:

- Create/cancel subscription.
- Change default payment method.
- Retry invoice payment manually.
- Mark invoice uncollectible.
- Cancel scheduled invoice retry.

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

Continue from the S1/S2 foundation toward the merchant subscription-link workflow:

- [ ] Add dashboard subscription list/detail and create subscription link from a customer.
- [ ] Add public `/subscribe/{token}` lookup API/page that shows customer, items, price, interval, and trial terms.
- [ ] Wire customer-present first payment/payment-method authorization through the normal checkout SDK/provider flow.
- [ ] Activate subscription only after first payment/authorization succeeds.
- [ ] Add invoices and idempotent period generation after activation semantics are tested.
- [ ] Do not add recurring retry execution until invoice state and activation are stable.

This keeps the workflow testable without introducing background money movement before invoice/payment state transitions are explicit.
