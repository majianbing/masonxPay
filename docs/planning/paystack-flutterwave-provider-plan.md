# Paystack and Flutterwave Provider Plan

## Current State

- MasonXPay is suitable for local and lab evaluation with Docker Compose, test credentials, and the Mason Simulator.
- MasonXPay core services are designed with production use in mind, including idempotency, payment state management, provider routing, observability, and performance testing.
- Production readiness depends on the adopter's deployment environment, including infrastructure hardening, network and permission isolation, secret management, compliance controls, capacity validation, and operational procedures.
- The public deployment should currently be treated as a reference architecture.
- Implemented PSP connectors are Stripe, Square, Braintree, Mollie, Flutterwave sandbox hosted checkout, Paystack hosted checkout, and TEST-only Mason Simulator.
- Paystack is implemented for hosted checkout, refund, sync-status, signed webhooks, dashboard connector setup, SDK checkout, and TEST connector preview. Manual sandbox verification is blocked until the Paystack account is activated with required entity/business information.
- Flutterwave is implemented for self-host sandbox evaluation. Production readiness still requires live-mode provider verification and adopter-owned hardening.

## Goals

- Make MasonXPay self-hostable for Paystack and Flutterwave sandbox evaluation.
- Add both connectors through the same provider abstraction as existing PSPs.
- Keep the PCI boundary intact: raw PAN, CVV, and full card data must never enter MasonXPay core services.
- Support TEST mode first, with LIVE mode gated by explicit documentation and verification.
- Preserve tenant scope, TEST/LIVE isolation, idempotency, webhook verification, and existing routing behavior.

## Non-Goals

- No direct-card charge API that requires MasonXPay to collect PAN/CVV.
- No claim of production readiness based only on sandbox success.
- No automatic migration of existing connector data.
- No Africa-specific payout, transfer, split-payment, marketplace, or settlement-file support in the first connector pass.
- No cross-provider reuse of provider-scoped tokens unless a future portable-instrument integration is explicitly added.

## Provider Approach

Use hosted or provider-side checkout flows first.

Paystack:

- Use Paystack test/live secret keys as encrypted credentials.
- Use a public-key or provider sentinel in `provider_config` so checkout can expose the provider safely.
- Prefer transaction initialization or inline-hosted flow where MasonXPay creates the provider transaction server-side and the browser redirects or opens the provider checkout.
- Verify completed transactions server-side before marking a MasonXPay payment as succeeded.
- Verify webhook signatures before processing provider events.

Flutterwave:

- Use Flutterwave sandbox/live secret key or access token as encrypted credentials.
- Use provider environment config to select sandbox vs production base URL.
- Prefer hosted/redirect checkout or provider-side payment-method creation.
- Support Flutterwave sandbox scenario keys only as TEST-mode tooling, never as LIVE-mode connector config.
- Verify completed charges server-side before marking a MasonXPay payment as succeeded.
- Verify webhook signatures before processing provider events.

Provider docs to re-check immediately before implementation:

- Paystack API and test payments: https://paystack.com/docs/api/ and https://paystack.com/docs/payments/test-payments/
- Flutterwave environments, testing, and card payments: https://developer.flutterwave.com/docs/environments, https://developer.flutterwave.com/docs/testing, and https://developer.flutterwave.com/docs/card

## Implementation Plan

### PF0 - Readiness and Copy Alignment

Status: [x] Flutterwave and Paystack docs are aligned with implemented hosted checkout support; Paystack manual sandbox verification is blocked by account/entity activation.

- [x] Audit README, hosted website copy, dashboard connector labels, and demo copy for Paystack/Flutterwave claims.
- [x] Change public language so Flutterwave is marked integrated for TEST hosted checkout and Paystack is marked implemented with manual sandbox verification blocked by provider account/entity activation.
- [x] Add a clear readiness statement:
  - lab/self-host evaluation: yes;
  - core services: designed with production use in mind;
  - public deployment: reference architecture;
  - production adoption: requires environment-specific hardening, compliance controls, operational procedures, and capacity validation.
- [x] Add a short self-host evaluation path that points to Docker Compose and TEST connectors.

Acceptance:

- No public doc says Paystack is production-ready before live-mode verification and adopter-owned hardening.
- Public docs mark Flutterwave as TEST hosted checkout support, not broad production readiness.
- Root README clearly distinguishes core-service design intent, public reference deployment status, and adopter-owned production hardening.

### PF1 - Provider Model and Credentials

Status: [x] Flutterwave and Paystack complete.

- [x] Add `FLUTTERWAVE` to `PaymentProvider`.
- [x] Add `FlutterwaveCredentials` to the sealed `ProviderCredentials` hierarchy.
- [x] Add Paystack provider model and credentials.
- [x] Extend `CreateProviderAccountRequest` with provider-specific fields:
  - Paystack: secret key, public key if required by the selected checkout flow.
  - Flutterwave: secret key/access token, public key or checkout config if required.
- [x] Extend `CredentialsCodec` encode/decode/client-key behavior for Flutterwave and Paystack.
- [x] Keep Flutterwave secrets in encrypted credentials and only client-safe identifiers in `provider_config`.
- [x] Keep Paystack secrets in encrypted credentials and only client-safe identifiers in `provider_config`.
- [x] Add unit tests for Flutterwave credential encoding, decoding, and client key visibility.
- [x] Add unit tests for Paystack credential encoding, decoding, and client key visibility.

Acceptance:

- Connector creation can persist and reload Flutterwave and Paystack credentials without exposing secrets.
- Checkout sessions expose only client-safe config.

### PF2 - Paystack Sandbox Connector

Status: [x] Implemented; manual Paystack sandbox verification blocked by provider account/entity activation.

- [x] Add `PaystackPaymentProviderService` implementing `PaymentProviderService`.
- [x] Implement initial hosted/redirect charge path.
- [x] Map provider statuses to MasonXPay `PaymentIntentStatus`.
- [x] Implement refunds against provider transaction references.
- [x] Implement sync-status for stale or redirected payments.
- [x] Implement cancel/capture only if supported by the selected Paystack flow; otherwise return unsupported cleanly.
- [x] Add Paystack webhook controller with signature verification and inbound event deduplication.
- [~] Add tests for successful payment, failed payment, refund, webhook verification failure, duplicate webhook, and idempotent confirm retry.

Acceptance:

- A self-hosted TEST Paystack connector can run a preview payment link end to end in sandbox after Paystack account/entity activation.
- MasonXPay does not receive raw card data.

### PF3 - Flutterwave Sandbox Connector

Status: [x] Hosted checkout, refund, sync, webhook verification/dedupe, capability seed, SDK/dashboard wiring, unit tests, and manual provider sandbox callback flow are verified.

- [x] Add `FlutterwavePaymentProviderService` implementing `PaymentProviderService`.
- [x] Implement initial hosted/redirect charge path.
- [x] Map provider statuses and failure codes to MasonXPay outcomes.
- [x] Implement refunds against provider transaction references.
- [x] Implement sync-status for redirected or in-flight payments.
- [x] Implement cancel/capture only if supported by the selected Flutterwave flow; otherwise return unsupported cleanly.
- [x] Add Flutterwave webhook controller with signature verification and inbound event deduplication.
- [ ] Add TEST-mode-only scenario-key support for connector preview if the hosted flow supports passing it safely.
- [~] Add tests for successful payment, failed payment, refund, webhook verification failure, duplicate webhook, and idempotent confirm retry.

Acceptance:

- A self-hosted TEST Flutterwave connector can run a preview payment link end to end in sandbox.
- Scenario simulation cannot be enabled in LIVE mode.
- MasonXPay does not receive raw card data.

### PF4 - Dashboard and Browser SDK

Status: [x] Flutterwave and Paystack complete.

- [x] Add Flutterwave provider metadata, credential fields, validation, and branding to the dashboard connector form.
- [x] Add Flutterwave checkout handler in `sdk/browser/src/index.ts`.
- [x] Use existing redirect action handling.
- [x] Update connector preview with Flutterwave test cards and supported test scenarios.
- [x] Rebuild dashboard public SDK bundle after SDK changes.
- [x] Add Paystack provider metadata, credential fields, validation, and branding to the dashboard connector form.
- [x] Add Paystack checkout handler in `sdk/browser/src/index.ts`.
- [x] Update connector preview with Paystack test cards and supported test scenarios.
- [x] Add Paystack dashboard and SDK support.

Acceptance:

- Merchants can create TEST Flutterwave and Paystack connectors from the dashboard.
- Flutterwave and Paystack preview links show the selected provider and complete through hosted sandbox checkout after provider account verification.

### PF5 - Routing, Capabilities, and Operations

Status: [x] Flutterwave and Paystack complete.

- [x] Seed default card capabilities for Flutterwave connector accounts.
- [x] Add Flutterwave provider failure-code mappings for routing retry/fallback categories.
- [x] Ensure route policies can select Flutterwave through existing connector/capability flows.
- [x] Add provider-specific limitations to docs so merchants understand unsupported manual capture or off-session behavior.
- [x] Add Paystack capabilities, mappings, and route-policy coverage.

Acceptance:

- Flutterwave and Paystack connectors participate in existing route policy selection.
- Unsupported operations fail explicitly without corrupting payment state.

### PF6 - Documentation and Lab Verification

Status: [~] Flutterwave docs and manual callback verification complete; Paystack docs complete and manual sandbox verification is blocked by account/entity activation.

- [x] Update root README supported connector table with provider name, status, and required credentials.
- [x] Add a Paystack sandbox walkthrough.
- [x] Add a Flutterwave sandbox walkthrough.
- [x] Add Flutterwave webhook local testing guidance, including tunnel setup and signature verification notes.
- [x] Run backend compile/tests and dashboard/browser SDK builds.
- [~] Manually verify:
  - Docker self-host boot;
  - connector creation;
  - preview payment link;
  - successful sandbox payment;
  - failed sandbox payment;
  - refund;
  - webhook duplicate handling;
  - TEST/LIVE isolation.

Acceptance:

- A new evaluator can self-host MasonXPay and test Flutterwave/Paystack from docs alone, subject to provider-account sandbox access and Paystack account/entity activation.
- The docs explicitly say the public deployment is a reference architecture and production readiness depends on environment-specific hardening.

## Test Plan

Backend:

- `cd backend && mvn compile`
- `cd backend && mvn -pl gateway-service -am test`

Dashboard:

- `cd dashboard && npm run build`

SDK:

- `cd sdk/browser && npm run build && npm run bundle`

Manual:

- `docker compose up --build`
- Add TEST connector for Flutterwave or Paystack.
- Create connector preview links.
- Complete sandbox success and failure payments.
- Trigger refund flows.
- Send valid and invalid webhook payloads.

## Production Readiness Gates

Flutterwave should not be marked production-ready until all of these are true:

- Provider contracts are verified against current official docs.
- Webhook signature verification has negative tests.
- Idempotency behavior is verified for confirm and refund retries.
- No raw card data enters backend logs, database rows, Kafka events, Redis values, or frontend telemetry.
- TEST/LIVE mode isolation is covered by tests.
- Provider outage and stale redirect reconciliation paths are tested.
- Refund and failure-code semantics are documented.
- At least one end-to-end live-mode smoke test is performed with a controlled real provider account and non-sensitive test merchant data.

Paystack should not be marked production-ready until PF2, dashboard/SDK wiring, docs, account/entity activation, manual sandbox verification, and live-mode gates are complete.

## Open Questions

- Which provider flow should be first for each connector: hosted redirect, inline JS, or provider payment links?
- Does the public website live in this repository or another repo? If another repo, that copy needs a separate PR.
- Should Paystack and Flutterwave be limited to card payments initially, or should local payment methods be surfaced in checkout once the hosted flow is stable?
- Do we want both providers in one PR, or one provider per PR after the shared readiness/docs cleanup?
