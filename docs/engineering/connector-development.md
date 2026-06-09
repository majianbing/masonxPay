# Connector Development

Follow this order when adding a new payment provider. Each layer depends on the previous one.

## Backend

Enum and credentials:

- Add a value to `PaymentProvider`.
- Create `XxxCredentials` implementing `ProviderCredentials`.
- If the credentials type is part of a sealed interface, add it to `permits`.

Credential codec:

- `encode`: serialize credentials to the encrypted blob.
- `decode`: deserialize and return the correct credentials type.
- `clientKeyFor`: return the publishable/client key for the browser SDK. Returning `null` silently hides the provider from checkout.
- `clientConfigFor`: return extra browser config when needed, for example Square `locationId`.

DTO and service:

- Add credential fields to `CreateProviderAccountRequest`.
- Create `XxxPaymentProviderService` implementing `PaymentProviderService`.
- Register it as `@Service`; `PaymentProviderDispatcher` discovers provider services.
- Implement charge, refund, capture, cancel, sync, and reusable-method behavior as applicable.

Webhook:

- Add `POST /api/v1/providers/xxx/webhook`.
- Verify the provider signature before processing.
- Reconcile through normal payment services.
- Deduplicate inbound provider events.

Dependencies:

- Add the provider SDK dependency to `backend/pom.xml` when needed.
- Verify with `cd backend && mvn compile`.

Dynamic client token:

- Add a public token endpoint only when required by the provider, such as Braintree.
- Keep public endpoints under `/pub/**` and return only client-safe data.

## Dashboard

- Add provider metadata to the connector page provider map.
- Add credential fields to the form schema and validation.
- Add provider-specific credential inputs.
- Add brand metadata and icon/color in the provider branding helpers.
- Keep TEST/LIVE mode explicit in connector forms and queries.

## Browser SDK

All client-side payment UI lives in `sdk/browser/src/index.ts`.

- Add `private buildXxxForm(opt, container): Promise<void>`.
- Add `private submitXxx(): Promise<void>`.
- Update `selectProvider()` to build the provider form.
- Update `submit()` to submit the provider form.
- Update `destroyProviderForms()` to tear down provider SDK state.
- Update `brandName()`.

Builders receive a pre-attached hidden slot and populate it. They must not manage skeleton loading or disabled state.

## Provider Client Key Pattern

| Provider | `clientKey` from checkout session | Client-side init |
|---|---|---|
| Stripe | `publishableKey` | `Stripe(clientKey)` then `stripe.elements({ clientSecret })` |
| Square | `applicationId` | `Square.payments(clientKey, locationId)` |
| Braintree | `merchantId` | Fetch `/pub/braintree-client-token` first |

## Connector Preview

Connector preview works automatically after SDK wiring. `POST /{accountId}/preview-link` creates a short-lived TEST payment link with `pinned_connector_id`, which scopes checkout to one account and bypasses routing during tokenization.

## Documentation

Update the root README supported connector list with:

- provider name
- sandbox signup link
- required credentials

## Planned Connectors

- [x] Stripe
- [x] Square
- [x] Braintree
- [x] Mollie
- [ ] Razorpay, skipped for now because sandbox access requires full KYC onboarding with no bypass
