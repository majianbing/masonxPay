# Paystack and Flutterwave Provider Plan

## Current State

- MasonXPay is suitable for local and lab evaluation with Docker Compose, test credentials, and the Mason Simulator.
- MasonXPay core services are designed with production use in mind, including idempotency, payment state management, provider routing, observability, and performance testing.
- Production readiness depends on the adopter's deployment environment, including infrastructure hardening, network and permission isolation, secret management, compliance controls, capacity validation, and operational procedures.
- The public deployment should currently be treated as a reference architecture.
- Implemented PSP connectors are Stripe, Square, Braintree, Mollie, and TEST-only Mason Simulator.
- Paystack and Flutterwave are not currently implemented in the backend provider dispatcher, dashboard connector forms, or browser SDK checkout.
- If any public website copy suggests Paystack or Flutterwave are currently usable, it should be corrected to "planned" until the full connector path is implemented and verified.

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

Status: [ ]

- Audit README, hosted website copy, dashboard connector labels, and demo copy for Paystack/Flutterwave claims.
- Change public language to "planned" until connector support is implemented.
- Add a clear readiness statement:
  - lab/self-host evaluation: yes;
  - core services: designed with production use in mind;
  - public deployment: reference architecture;
  - production adoption: requires environment-specific hardening, compliance controls, operational procedures, and capacity validation.
- Add a short self-host evaluation path that points to Docker Compose and TEST connectors.

Acceptance:

- No public doc says Paystack or Flutterwave are currently available before implementation.
- Root README clearly distinguishes core-service design intent, public reference deployment status, and adopter-owned production hardening.

### PF1 - Provider Model and Credentials

Status: [ ]

- Add `PAYSTACK` and `FLUTTERWAVE` to `PaymentProvider`.
- Add `PaystackCredentials` and `FlutterwaveCredentials` to the sealed `ProviderCredentials` hierarchy.
- Extend `CreateProviderAccountRequest` with provider-specific fields:
  - Paystack: secret key, public key if required by the selected checkout flow.
  - Flutterwave: secret key/access token, public key or checkout config if required.
- Extend `CredentialsCodec` encode/decode/client-key behavior.
- Keep all secrets in encrypted credentials and only client-safe identifiers in `provider_config`.
- Add unit tests for credential encoding, decoding, client key visibility, and TEST/LIVE mode handling.

Acceptance:

- Connector creation can persist and reload Paystack/Flutterwave credentials without exposing secrets.
- Checkout sessions expose only client-safe config.

### PF2 - Paystack Sandbox Connector

Status: [ ]

- Add `PaystackPaymentProviderService` implementing `PaymentProviderService`.
- Implement initial hosted/redirect charge path.
- Map provider statuses to MasonXPay `PaymentIntentStatus`.
- Implement refunds against provider transaction references.
- Implement sync-status for stale or redirected payments.
- Implement cancel/capture only if supported by the selected Paystack flow; otherwise return unsupported cleanly.
- Add Paystack webhook controller with signature verification and inbound event deduplication.
- Add tests for successful payment, failed payment, refund, webhook verification failure, duplicate webhook, and idempotent confirm retry.

Acceptance:

- A self-hosted TEST Paystack connector can run a preview payment link end to end in sandbox.
- MasonXPay does not receive raw card data.

### PF3 - Flutterwave Sandbox Connector

Status: [ ]

- Add `FlutterwavePaymentProviderService` implementing `PaymentProviderService`.
- Implement initial hosted/redirect charge path.
- Map provider statuses and failure codes to MasonXPay outcomes.
- Implement refunds against provider transaction references.
- Implement sync-status for redirected or in-flight payments.
- Implement cancel/capture only if supported by the selected Flutterwave flow; otherwise return unsupported cleanly.
- Add Flutterwave webhook controller with signature verification and inbound event deduplication.
- Add TEST-mode-only scenario-key support for connector preview if the hosted flow supports passing it safely.
- Add tests for successful payment, failed payment, refund, webhook verification failure, duplicate webhook, and idempotent confirm retry.

Acceptance:

- A self-hosted TEST Flutterwave connector can run a preview payment link end to end in sandbox.
- Scenario simulation cannot be enabled in LIVE mode.
- MasonXPay does not receive raw card data.

### PF4 - Dashboard and Browser SDK

Status: [ ]

- Add provider metadata, credential fields, validation, and branding to the dashboard connector form.
- Add Paystack and Flutterwave checkout handlers in `sdk/browser/src/index.ts`.
- Use existing redirect action handling where possible.
- Update connector preview instructions with sandbox credential setup and supported test scenarios.
- Rebuild dashboard public SDK bundle after SDK changes.

Acceptance:

- Merchants can create TEST Paystack/Flutterwave connectors from the dashboard.
- Preview links show the selected provider and complete through sandbox checkout.

### PF5 - Routing, Capabilities, and Operations

Status: [ ]

- Seed default card capabilities for Paystack and Flutterwave connector accounts.
- Add provider failure-code mappings for routing retry/fallback categories.
- Ensure route policies can select each provider.
- Add metrics tags and health display coverage.
- Add provider-specific limitations to docs so merchants understand unsupported manual capture or off-session behavior.

Acceptance:

- New connectors participate in existing route policy selection.
- Unsupported operations fail explicitly without corrupting payment state.

### PF6 - Documentation and Lab Verification

Status: [ ]

- Update root README supported connector table with provider name, status, sandbox setup link, and required credentials.
- Add a Paystack sandbox walkthrough.
- Add a Flutterwave sandbox walkthrough.
- Add webhook local testing guidance, including tunnel setup and signature verification notes.
- Run backend compile/tests and dashboard/browser SDK builds.
- Manually verify:
  - Docker self-host boot;
  - connector creation;
  - preview payment link;
  - successful sandbox payment;
  - failed sandbox payment;
  - refund;
  - webhook duplicate handling;
  - TEST/LIVE isolation.

Acceptance:

- A new evaluator can self-host MasonXPay and test Paystack or Flutterwave from docs alone.
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
- Add TEST connector for Paystack.
- Add TEST connector for Flutterwave.
- Create connector preview links for both.
- Complete sandbox success and failure payments.
- Trigger refund flows.
- Send valid and invalid webhook payloads.

## Production Readiness Gates

Paystack and Flutterwave should not be marked production-ready until all of these are true:

- Provider contracts are verified against current official docs.
- Webhook signature verification has negative tests.
- Idempotency behavior is verified for confirm and refund retries.
- No raw card data enters backend logs, database rows, Kafka events, Redis values, or frontend telemetry.
- TEST/LIVE mode isolation is covered by tests.
- Provider outage and stale redirect reconciliation paths are tested.
- Refund and failure-code semantics are documented.
- At least one end-to-end live-mode smoke test is performed with a controlled real provider account and non-sensitive test merchant data.

## Open Questions

- Which provider flow should be first for each connector: hosted redirect, inline JS, or provider payment links?
- Does the public website live in this repository or another repo? If another repo, that copy needs a separate PR.
- Should Paystack and Flutterwave be limited to card payments initially, or should local payment methods be surfaced in checkout once the hosted flow is stable?
- Do we want both providers in one PR, or one provider per PR after the shared readiness/docs cleanup?
