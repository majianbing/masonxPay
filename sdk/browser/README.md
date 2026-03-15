# @gateway/browser

Browser SDK for the MasonXPay Gateway — embeds a payment form directly on your page.

Uses your `pk_xxx` **publishable key** (safe to expose in the browser).
Card details never touch your server — the SDK returns a short-lived `gw_tok_xxx` that
your server exchanges for a real charge via [`@gateway/server`](../server/README.md).

## How it works (Pattern B)

```
Browser                         Your server                  Gateway API
──────                          ───────────                  ───────────
pk_xxx → mount form             sk_xxx → create intent
customer fills card          ←  send gatewayToken
         tokenize()
gw_tok_xxx ─────────────────►  confirm(gw_tok_xxx) ──────►  charge provider
```

1. Mount the form with `pk_xxx` — no server call needed upfront
2. Customer fills card and clicks Pay
3. Browser SDK tokenizes with Stripe/Square → calls `/pub/tokenize` → returns `gw_tok_xxx`
4. Your JS sends `gw_tok_xxx` + order info to **your server**
5. Your server calls `gateway.paymentIntents.create()` then `confirm(gw_tok_xxx)`

---

## Usage — Script tag (no build step)

### Full version (development / debugging)

```html
<script src="https://pay.yourgateway.com/gateway-sdk.js"></script>
```

### Compressed version (production)

```html
<script src="https://pay.yourgateway.com/gateway-sdk.min.js"></script>
```

Both expose `window.GatewayEmbedded` as a global.

### Example

```html
<!DOCTYPE html>
<html>
<head>
  <script src="/gateway-sdk.min.js"></script>
</head>
<body>
  <div id="payment-form"></div>
  <script>
    const gw = new GatewayEmbedded('pk_test_YOUR_PUBLISHABLE_KEY', {
      baseUrl: 'https://api.yourgateway.com',
    });

    gw.mount('#payment-form')
      .then(() => console.log('form ready'))
      .catch(err => console.error('failed to load:', err));

    gw.on('token', async ({ gatewayToken, provider }) => {
      // Send to YOUR server — never confirm client-side with sk_xxx
      const res = await fetch('/your-server/pay', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ gatewayToken, amount: 4200, currency: 'usd' }),
      });
      const result = await res.json();
      if (result.success) window.location = '/thank-you';
    });

    gw.on('error', ({ message }) => {
      console.error('Payment error:', message);
    });
  </script>
</body>
</html>
```

---

## Usage — npm / ES module (TypeScript projects)

```bash
npm install @gateway/browser
```

```typescript
import { GatewayEmbedded } from '@gateway/browser';

const gw = new GatewayEmbedded('pk_test_YOUR_PUBLISHABLE_KEY', {
  baseUrl: 'https://api.yourgateway.com',
});

await gw.mount('#payment-form');

gw.on('token', async ({ gatewayToken }) => {
  await fetch('/your-server/pay', {
    method: 'POST',
    body: JSON.stringify({ gatewayToken, amount: 4200, currency: 'usd' }),
  });
});
```

---

## API

### `new GatewayEmbedded(publishableKey, options?)`

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `baseUrl` | `string` | `http://localhost:8080` | Your gateway API base URL |

### `.mount(selector)` → `Promise<this>`

Fetches available providers from the gateway, renders the payment picker and card form
into `selector` (CSS selector string or `HTMLElement`). Returns `this` for chaining.

The form automatically shows Stripe or Square card inputs depending on which connectors
the merchant has configured. If multiple providers are active, a provider picker is shown.
Square automatically includes Google Pay, Apple Pay, and Cash App where available.

### `.on(event, handler)` → `this`

| Event | Payload | When |
|-------|---------|------|
| `token` | `{ gatewayToken: string, provider: string }` | Card tokenized, ready to confirm server-side |
| `error` | `{ message: string }` | Tokenization or network error |
| `ready` | `void` | Form mounted and provider SDK loaded |

---

## Completing the payment server-side

After receiving `gw_tok_xxx` in your `token` handler, your server does:

```typescript
// server.ts — using @gateway/server
import { GatewayNode } from '@gateway/server';
const gateway = new GatewayNode('sk_test_xxx', { merchantId: '...' });

app.post('/your-server/pay', async (req, res) => {
  const { gatewayToken, amount, currency } = req.body;

  const intent = await gateway.paymentIntents.create({
    amount,
    currency,
    idempotencyKey: `order-${Date.now()}`,
  });

  const result = await gateway.paymentIntents.confirm(intent.id, {
    paymentMethodId: gatewayToken, // gw_tok_xxx — routing already resolved browser-side
  });

  res.json({ success: result.status === 'SUCCEEDED', intentId: result.id });
});
```

---

## Building from source

### Prerequisites

```bash
cd sdk/browser
npm install
```

### Compile TypeScript (for npm consumers)

Outputs type-safe `.js` + `.d.ts` files to `dist/`:

```bash
npm run build
```

### Build compressed bundle (for `<script>` tag consumers)

Outputs a single minified IIFE to `dist/gateway-sdk.min.js`:

```bash
npm run bundle
```

Copy the output to wherever you serve static assets:

```bash
cp dist/gateway-sdk.min.js ../../dashboard/public/gateway-sdk.min.js
# or to your CDN upload folder
cp dist/gateway-sdk.min.js /path/to/cdn/gateway-sdk.min.js
```

### File sizes (reference)

| File | Size | Use |
|------|------|-----|
| `gateway-sdk.js` | ~13 KB | Development, debugging |
| `gateway-sdk.min.js` | ~6 KB | Production |
