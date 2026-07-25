# Paystack Sandbox Guide

Paystack is integrated as a hosted redirect checkout provider. MasonXPay initializes the transaction server-side, opens the Paystack checkout URL through the existing `redirect_url` iframe flow, then verifies the transaction through Paystack before marking a payment as final. Raw PAN, CVV, and OTP values stay on Paystack pages.

Manual sandbox verification is currently blocked unless the Paystack account is activated with the required entity/business information. This is a provider-account onboarding requirement, not a MasonXPay code blocker.

## Credentials

In Paystack Dashboard, use TEST mode keys:

- Secret key: `sk_test_...` (required, encrypted at rest)
- Public key: `pk_test_...` (optional, stored as client-safe connector config)

Create a TEST connector in MasonXPay Dashboard -> Connectors -> Add Connector -> Paystack.

## Webhook

Register this endpoint in Paystack:

```text
POST /api/v1/providers/paystack/webhook
```

Paystack signs the raw request payload with `x-paystack-signature`. MasonXPay verifies HMAC-SHA512 with the connector secret key, deduplicates events by `event:reference`, and fetches `/transaction/verify/{reference}` before changing payment state.

For local testing, expose the backend with a tunnel and register:

```text
https://YOUR-TUNNEL/api/v1/providers/paystack/webhook
```

## Preview Flow

1. In Dashboard -> Connectors, add a TEST Paystack connector.
2. Open the connector preview page.
3. Set amount and currency, then start preview checkout.
4. Click Pay with Paystack.
5. Enter a Paystack test card on Paystack checkout.
6. Complete PIN/OTP steps if prompted.
7. MasonXPay polls `payment-status`, verifies the Paystack reference, and shows the final result.

If Paystack shows an account activation or entity-information requirement before checkout can run, complete that provider onboarding step first. Until then, MasonXPay verification remains limited to backend unit tests, SDK/dashboard builds, and connector setup screens.

## Test Cards

Use Paystack TEST credentials only.

| Scenario | Card | Expiry | CVV | PIN | OTP |
|---|---|---|---|---|---|
| Visa success | `4084 0840 8408 4081` | `07/27` | `408` | | |
| Verve success | `5078 5078 5078 5078 12` | `07/27` | `081` | `1111` | |
| Verve PIN + OTP success | `5060 6666 6666 6666 666` | `07/27` | `123` | `1234` | `123456` |
| Verve OTP success | `5078 5078 5078 5078 04` | `07/27` | `884` | `0000` | `123456` |
| Declined | `4084 0800 0000 5408` | `07/27` | `001` | | |
| Token not generated | `5078 5078 5078 5078 53` | `07/27` | `082` | `0000` | |

## Limits

- Manual capture is not supported for this hosted checkout path.
- Off-session reuse of Paystack-scoped tokens is not implemented.
- Manual sandbox checkout verification requires a Paystack account that has passed the provider's activation/entity-information gate.
- Live-mode production use requires adopter-owned KYC, compliance review, live webhook verification, and a controlled live smoke test.
