# @gateway/browser

Browser SDK for the MasonXPay Gateway.

Two integration modes:

| Mode | When to use | Entry point |
|------|-------------|-------------|
| **Hosted checkout** | Pay pages, payment links, connector preview | `mountCheckout()` |
| **Embedded SDK** | Merchant's own checkout page, full control | `mount()` |

---

## Mode 1 — Hosted checkout (recommended)

The gateway creates a short-lived payment link. Your page (or the gateway's hosted pay page) mounts the SDK with a `linkToken`. The SDK fetches the session, renders the provider picker and payment form, and calls `onSuccess`/`onError` when done.

Card details never touch your server. Routing, provider selection, and charge all happen inside the gateway.

```typescript
import { GatewayEmbedded } from '@gateway/browser';

const gw = new GatewayEmbedded('', { baseUrl: 'https://api.yourgateway.com' });

await gw.mountCheckout('#checkout-container', {
  linkToken: 'lnk_xxx',           // short-lived token from your server
  onSuccess: (result) => {
    console.log(result.paymentIntentId); // fulfilled
    window.location.href = '/thank-you';
  },
  onError: (err) => {
    console.error(err.message);
  },
});
```

**How the hosted flow works:**

```
Your server                    Browser                         Gateway API
──────────                     ───────                         ───────────
POST /payment-links    ──────► linkToken
                               mountCheckout(linkToken)
                               GET /pub/checkout-session ────► providers + amount
                               render provider picker
                               customer selects + fills
                               POST /pub/tokenize        ────► gw_tok_xxx
                               POST /pub/pay/{token}/checkout → charge provider
                               onSuccess({ paymentIntentId })
```

### Stripe redirect methods (iDEAL, Amazon Pay, Sofort)

For redirect-based Stripe methods the SDK handles the full round-trip automatically:

1. `submitStripe` calls `stripe.confirmPayment({ redirect: 'if_required' })`
2. Browser navigates to the provider (Amazon, bank, etc.) and returns to the same URL with `?payment_intent_client_secret=...`
3. On next `mountCheckout` call the SDK detects the param, calls `GET /pub/pay/{token}/stripe-result`, and fires `onSuccess`/`onError`

If your page loses state on redirect (e.g. a dashboard preview page), save the `linkToken` to `sessionStorage` before the SDK mounts so you can restore it on return.

---

## Mode 2 — Embedded SDK

Merchant hosts their own checkout page. The SDK renders the payment form, tokenizes the card, and emits a `gw_tok_xxx` gateway token that your server uses to confirm a payment intent.

```typescript
import { GatewayEmbedded } from '@gateway/browser';

const gw = new GatewayEmbedded('pk_test_YOUR_PUBLISHABLE_KEY', {
  baseUrl: 'https://api.yourgateway.com',
});

await gw.mount('#payment-form');

gw.on('token', async ({ gatewayToken, provider }) => {
  // Send to YOUR server — never confirm client-side
  const res = await fetch('/your-server/pay', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ gatewayToken, amount: 4200, currency: 'usd' }),
  });
  if ((await res.json()).success) window.location.href = '/thank-you';
});

gw.on('error', ({ message }) => console.error(message));
```

**Server-side confirm (Pattern B):**

```
Browser                         Your server                  Gateway API
──────                          ───────────                  ───────────
pk_xxx → mount form
customer fills card
         tokenize()
gw_tok_xxx ─────────────────►  confirm(gw_tok_xxx) ──────►  charge provider
```

Your server:

```typescript
import { GatewayNode } from '@gateway/server';
const gateway = new GatewayNode('sk_test_xxx', { merchantId: '...' });

app.post('/your-server/pay', async (req, res) => {
  const { gatewayToken, amount, currency } = req.body;
  const intent = await gateway.paymentIntents.create({
    amount, currency, idempotencyKey: `order-${Date.now()}`,
  });
  const result = await gateway.paymentIntents.confirm(intent.id, {
    paymentMethodId: gatewayToken,
  });
  res.json({ success: result.status === 'SUCCEEDED' });
});
```

---

## Installation

### npm / ES module

```bash
npm install @gateway/browser
```

### Script tag

```html
<script src="https://pay.yourgateway.com/gateway-sdk.min.js"></script>
<!-- exposes window.GatewayEmbedded -->
```

---

## API reference

### `new GatewayEmbedded(publishableKey, options?)`

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `baseUrl` | `string` | `http://localhost:8080` | Gateway API base URL |

Pass an empty string for `publishableKey` in hosted checkout mode — it is not used.

---

### `.mountCheckout(selector, options)` → `Promise<this>`

Hosted checkout mode. Fetches the checkout session for the given `linkToken`, renders the full payment UI (provider picker, form, pay button), and handles the complete payment lifecycle including Stripe redirect returns.

| Option | Type | Required | Description |
|--------|------|----------|-------------|
| `linkToken` | `string` | yes | Short-lived link token from your server or the gateway |
| `onSuccess` | `(result: CheckoutResult) => void` | yes | Called on successful payment |
| `onError` | `(err: Error) => void` | no | Called on failure |

**`CheckoutResult`**

```typescript
interface CheckoutResult {
  success: boolean;
  status: string;           // e.g. 'SUCCEEDED'
  paymentIntentId: string;
  redirectUrl?: string;     // present when payment link has a successUrl
  failureCode?: string;
  failureMessage?: string;
}
```

---

### `.mount(selector)` → `Promise<this>`

Embedded SDK mode. Fetches available providers using the publishable key, renders the payment form. Use `.on('token', handler)` to receive the gateway token.

---

### `.on(event, handler)` → `this`

| Event | Payload | When |
|-------|---------|------|
| `token` | `{ gatewayToken: string, provider: string }` | Card tokenized — embedded mode only |
| `error` | `{ message: string }` | Tokenization or network error |
| `ready` | `void` | Form mounted and provider SDK loaded |

---

### `.destroy()`

Tears down provider SDK instances (Stripe Elements, Square card, Braintree Drop-in), clears the container, and frees all internal state. Call this before unmounting the container element.

---

## Supported providers

| Provider | Card | Google Pay | Apple Pay | Wallets | Redirect methods |
|----------|------|-----------|-----------|---------|-----------------|
| Stripe | ✓ | ✓ (via Payment Element) | ✓ | ✓ | iDEAL, Amazon Pay, Sofort, Link |
| Square | ✓ | ✓ | ✓ | Cash App | — |
| Braintree | ✓ (Drop-in) | — | — | — | — |

---

## Building from source

```bash
cd sdk/browser
npm install
npm run build     # TypeScript → dist/ (for npm consumers)
npm run bundle    # minified IIFE → dist/gateway-sdk.min.js (for script-tag consumers)
```
