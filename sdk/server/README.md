# @gateway/server

Official Node.js / TypeScript SDK for the MasonXPay Gateway API.

**Server-side only** — requires your `sk_xxx` secret key. Never use this in a browser.
For browser-side card collection, see [`@gateway/browser`](../browser/README.md).

## Installation

```bash
npm install @gateway/server
```

## Quick start

```typescript
import { GatewayNode } from '@gateway/server';

const gateway = new GatewayNode('sk_test_YOUR_SECRET_KEY', {
  merchantId: 'YOUR_MERCHANT_ID',
  baseUrl: 'https://api.yourgateway.com', // default: http://localhost:8080
});
```

---

## Payment Intents

### Create

```typescript
const intent = await gateway.paymentIntents.create({
  amount: 4200,        // in cents
  currency: 'usd',
  idempotencyKey: 'order-abc-123',        // prevents duplicate charges on retry
  metadata: { orderId: 'order-abc-123' },
  successUrl: 'https://yourshop.com/thank-you',
  cancelUrl:  'https://yourshop.com/cancel',
});
// intent.status === 'REQUIRES_PAYMENT_METHOD'
```

### Confirm — with a raw provider token

For server-to-server flows where you already have a Stripe `pm_xxx` or Square nonce:

```typescript
const result = await gateway.paymentIntents.confirm(intent.id, {
  paymentMethodId: 'pm_card_visa', // Stripe test token, real pm_xxx, or Square nonce
  paymentMethodType: 'card',       // optional, defaults to 'card'
});
// result.status === 'SUCCEEDED' | 'FAILED'
```

### Confirm — with a gateway token from the browser SDK

When [`@gateway/browser`](../browser/README.md) collects the card in your customer's browser,
it returns a short-lived `gw_tok_xxx`. Pass it directly — the gateway resolves routing
automatically using the connector that was already selected during tokenization:

```typescript
app.post('/pay', async (req) => {
  const { gatewayToken, amount, currency } = req.body;

  const intent = await gateway.paymentIntents.create({
    amount,
    currency,
    idempotencyKey: `order-${req.body.orderId}`,
  });

  const result = await gateway.paymentIntents.confirm(intent.id, {
    paymentMethodId: gatewayToken, // 'gw_tok_xxx' from the browser
  });

  res.json({ success: result.status === 'SUCCEEDED', intentId: result.id });
});
```

### Retrieve

```typescript
const intent = await gateway.paymentIntents.retrieve('intent-uuid');
```

### Cancel

```typescript
await gateway.paymentIntents.cancel(intent.id);
```

---

## Refunds

```typescript
// Full refund
await gateway.paymentIntents.createRefund(intent.id);

// Partial refund
await gateway.paymentIntents.createRefund(intent.id, {
  amount: 1000,                  // cents
  reason: 'CUSTOMER_REQUEST',    // CUSTOMER_REQUEST | DUPLICATE | FRAUDULENT
});

// List refunds for an intent
const refunds = await gateway.paymentIntents.listRefunds(intent.id);
```

---

## API Keys

```typescript
const pair = await gateway.apiKeys.create({ name: 'Production keys', mode: 'LIVE' });
// pair.secretKey.plaintext is returned only once — store it securely immediately
// pair.publishableKey is safe to embed in your frontend

const keys = await gateway.apiKeys.list();
await gateway.apiKeys.revoke(pair.secretKey.id);
```

---

## Webhook Endpoints

```typescript
const endpoint = await gateway.webhookEndpoints.create({
  url: 'https://yourshop.com/webhooks/gateway',
  subscribedEvents: ['payment_intent.succeeded', 'payment_intent.failed'],
});
// Store endpoint.signingSecret — you will need it to verify incoming requests

await gateway.webhookEndpoints.update(endpoint.id, {
  subscribedEvents: [
    'payment_intent.succeeded',
    'payment_intent.failed',
    'refund.succeeded',
  ],
});

await gateway.webhookEndpoints.rotateSecret(endpoint.id);
await gateway.webhookEndpoints.delete(endpoint.id);
```

---

## Webhook Verification

Always verify the signature on every incoming webhook before processing it.

```typescript
import express from 'express';

const app = express();
// Must use raw body — do not parse as JSON before this middleware
app.use('/webhooks/gateway', express.raw({ type: 'application/json' }));

app.post('/webhooks/gateway', (req, res) => {
  const signature    = req.headers['x-gateway-signature'] as string;
  const signingSecret = process.env.GATEWAY_WEBHOOK_SECRET!;

  try {
    const event = gateway.webhooks.verify(req.body, signature, signingSecret);

    switch (event.eventType) {
      case 'payment_intent.succeeded':
        await fulfillOrder(event.resourceId);
        break;
      case 'payment_intent.failed':
        await notifyCustomer(event.resourceId);
        break;
    }

    res.json({ received: true });
  } catch (err) {
    // Signature mismatch — reject the request
    res.status(400).send(`Webhook error: ${err.message}`);
  }
});
```

---

## Routing Rules

```typescript
const rule = await gateway.routingRules.create({
  targetProvider: 'STRIPE',
  priority: 1,
  weight: 80,               // receives 80% of matched traffic
  currencies: ['USD', 'CAD'],
  amountMax: 100000,        // only apply to amounts ≤ $1,000
});

await gateway.routingRules.update(rule.id, { weight: 60, enabled: false });
await gateway.routingRules.delete(rule.id);
```

---

## Logs

```typescript
const page = await gateway.logs.list({ page: 0, size: 20 });
// page.content, page.totalElements, page.totalPages
```

---

## Error handling

All methods throw `GatewayError` on non-2xx responses:

```typescript
import { GatewayError } from '@gateway/server';

try {
  await gateway.paymentIntents.confirm(id, { paymentMethodId: 'pm_card_chargeDeclined' });
} catch (err) {
  if (err instanceof GatewayError) {
    console.log(err.status); // HTTP status, e.g. 402
    console.log(err.detail); // human-readable message from the API
  }
}
```

---

## Building from source

```bash
cd sdk/server
npm install
npm run build   # TypeScript → dist/
npm run dev     # watch mode
```
