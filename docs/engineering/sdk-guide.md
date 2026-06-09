# SDK Guide

The browser SDK is the single source of truth for client-side payment UI.

## Ownership

`sdk/browser/src/index.ts` owns:

- provider picker
- payment form inputs
- pay button
- loading skeleton
- result handling
- provider SDK lifecycle

Pages and apps are consumers. They call `mountCheckout()` and handle `onSuccess` / `onError`. They must not contain provider-specific JSX or payment SDK logic.

## Hosted Checkout Page

The hosted pay page at `dashboard/app/pay/[token]/page.tsx` should call:

```ts
gw.mountCheckout(containerEl, { linkToken, onSuccess, onError })
```

Do not add provider-specific React or JSX to the hosted pay page.

## Provider Form Lifecycle

`selectProvider()` owns the loading lifecycle:

- show skeleton before building
- pre-attach a hidden provider slot to the live DOM
- call the provider builder
- clear skeleton and reveal the slot after the builder resolves

Builders must not call skeleton helpers or manage disabled state. This is required for iframe-injecting SDKs such as Square and Braintree.

## Stripe Redirect Pattern

Hosted mode uses `stripe.confirmPayment({ redirect: 'if_required' })` so card flows can complete in place and redirect methods can leave/return.

On redirect return, `mountCheckout` detects `?payment_intent_client_secret`, calls `GET /pub/pay/{token}/stripe-result`, and fires `onSuccess` or `onError`.

`stripeClientSecret` is cached per `GatewayEmbedded` instance after the first Stripe selection and cleared only on `destroy()`. This prevents creating multiple payment intents when a customer switches providers back and forth.

## UI Details

- The pay button uses the SDK-owned sheen animation.
- Skeleton bars use the SDK-owned shimmer animation.
- Pages should not duplicate SDK payment UI states.

## References

- [Connector development](connector-development.md)
- [Routing and orchestration](../architecture/routing-orchestration.md)
