# Gateway Node.js SDK

The official Node.js and TypeScript library for the Gateway API.

## Installation

```bash
npm install @gateway/node
# or
yarn add @gateway/node
```

## Usage

Initialize the SDK with your secret key, which can be found in your merchant dashboard.

```typescript
import { GatewayNode } from '@gateway/node';

const gateway = new GatewayNode('sk_test_YOUR_SECRET_KEY', {
  merchantId: 'YOUR_MERCHANT_ID'
});
```

### Creating a Payment Intent

To create a payment, you create a `PaymentIntent` object.

```typescript
async function createPayment() {
  try {
    const paymentIntent = await gateway.paymentIntents.create({
      amount: 1000, // Amount in cents
      currency: 'usd',
      captureMethod: 'automatic',
      metadata: { orderId: 'order_123' },
      successUrl: 'https://yoursite.com/success',
      cancelUrl: 'https://yoursite.com/cancel',
    });

    console.log('PaymentIntent created:', paymentIntent);
    return paymentIntent;
  } catch (error) {
    console.error('Error creating PaymentIntent:', error);
  }
}
```

### Retrieving a Payment Intent

You can retrieve a payment intent by its ID.

```typescript
async function retrievePayment(paymentIntentId: string) {
  try {
    const paymentIntent = await gateway.paymentIntents.retrieve(paymentIntentId);
    console.log('PaymentIntent retrieved:', paymentIntent);
    return paymentIntent;
  } catch (error) {
    console.error('Error retrieving PaymentIntent:', error);
  }
}
```

### Managing API Keys

You can manage your API keys through the `apiKeys` resource.

```typescript
async function listApiKeys() {
  try {
    const apiKeys = await gateway.apiKeys.list();
    console.log('API Keys:', apiKeys);
    return apiKeys;
  } catch (error) {
    console.error('Error listing API Keys:', error);
  }
}
```

### Verifying Webhook Signatures

It's crucial to verify that incoming webhooks are from Gateway. The SDK provides a utility for this.

```typescript
import express from 'express';

const app = express();

// It's important to use the raw request body
app.use(express.raw({ type: 'application/json' }));

app.post('/webhook', (req, res) => {
  const signature = req.headers['stripe-signature'] as string;
  const signingSecret = 'whsec_...'; // Your webhook signing secret

  try {
    const event = gateway.webhooks.verify(req.body, signature, signingSecret);

    // Handle the event
    switch (event.type) {
      case 'payment.succeeded':
        // ...
        break;
      case 'payment.failed':
        // ...
        break;
      // ... other event types
    }

    res.json({ received: true });
  } catch (err) {
    res.status(400).send(`Webhook Error: ${err.message}`);
  }
});
```

## API Resources

The SDK is organized into the following resources:

*   `paymentIntents`
*   `webhookEndpoints`
*   `apiKeys`
*   `logs`

Each resource provides methods for interacting with the corresponding API endpoints. For more details on the available methods and their parameters, please refer to the source code and the main API documentation.
