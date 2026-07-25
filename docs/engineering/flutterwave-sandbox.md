# Flutterwave Sandbox Connector

Flutterwave is implemented as a hosted redirect checkout connector for TEST-mode self-host evaluation. MasonXPay creates the provider checkout server-side, stores the deterministic MasonXPay provider idempotency key as Flutterwave `tx_ref`, opens Flutterwave checkout in the browser SDK redirect overlay, and verifies final status server-side before marking the payment terminal.

## Status

| Capability | Status |
|---|---|
| TEST connector creation | Implemented |
| Hosted checkout payment | Implemented and manually verified |
| Redirect callback through `/pay/3ds-return` | Implemented and manually verified |
| Status sync after redirect | Implemented through Flutterwave `verify_by_reference` |
| Refund | Implemented by verifying `tx_ref` and refunding the provider transaction id |
| Webhook verification | Implemented with `verif-hash` and provider re-verification |
| Manual capture / cancel | Not supported for hosted checkout; fails explicitly |
| Live-mode production readiness | Not claimed |

## Required Credentials

Create a Flutterwave TEST connector from the dashboard Connectors page:

| Field | Required | Notes |
|---|---|---|
| Secret Key | Yes | Flutterwave server-side key. Encrypted at rest. |
| Public Key | No | Stored as client-safe config for future inline flows. Hosted checkout works without it. |
| Webhook Hash | Recommended | Flutterwave sends this as the `verif-hash` header. Webhooks are rejected if no hash is configured. |

## Preview Flow

1. Start the local stack and open the dashboard.
2. Create a TEST Flutterwave connector.
3. Open the connector preview page.
4. Launch checkout.
5. The browser SDK opens Flutterwave checkout in the redirect iframe overlay.
6. Complete the test card flow. Flutterwave may open a new tab for OTP/authentication.
7. Flutterwave redirects back to `/pay/3ds-return?linkToken=...`.
8. The return page posts `gw:3ds_complete` to the SDK overlay.
9. The SDK polls `/pub/pay/{token}/payment-status`.
10. The backend verifies the Flutterwave transaction by `tx_ref` and returns the final status.

The dashboard middleware must leave `/pay/**` and `/subscribe/**` public. These routes are checkout surfaces, not authenticated merchant-dashboard pages.

## Test Cards

Use Flutterwave TEST credentials only.

| Scenario | Card | Expiry | CVV | PIN | OTP |
|---|---|---|---|---|---|
| Mastercard PIN success | `5531886652142950` | `09/32` | `564` | `3310` | `12345` |
| Mastercard 3DS success | `5438898014560229` | `10/31` | `564` | `3310` | `12345` |
| Visa 3DS success | `4187427415564246` | `09/32` | `828` | `3310` | `12345` |
| Visa AVS success | `4556052704172643` | `09/32` | `899` | `3310` | `12345` |
| Do Not Honour | `5143010522339965` | `08/32` | `276` | `3310` | `12345` |
| Insufficient funds | `5258585922666506` | `09/31` | `883` | `3310` | `12345` |
| Incorrect PIN | `5399834697894723` | `09/31` | `883` | `3310` | `12345` |

## Webhooks

Endpoint:

```text
POST /api/v1/providers/flutterwave/webhook
```

For local testing, expose the backend with a tunnel and configure the full webhook URL in Flutterwave. The connector's webhook hash must match the incoming `verif-hash` header.

Webhook processing is intentionally defensive:

- Reject invalid JSON.
- Find the local payment intent by `data.tx_ref`.
- Verify the connector is Flutterwave.
- Verify `verif-hash`.
- Deduplicate the provider event.
- Fetch the transaction from Flutterwave before mutating local payment state.

## Limitations

- MasonXPay does not collect raw PAN, CVV, or cardholder authentication data.
- Manual capture and cancel are unsupported for the hosted checkout flow.
- Flutterwave scenario-key support is not currently passed through connector preview.
- Live-mode production readiness requires a separate controlled live smoke test, operational hardening, and compliance review.
