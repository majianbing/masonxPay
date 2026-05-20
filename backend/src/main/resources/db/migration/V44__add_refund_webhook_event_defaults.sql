ALTER TABLE webhook_endpoints
    ALTER COLUMN subscribed_events SET DEFAULT 'payment_intent.succeeded,payment_intent.failed,payment_intent.canceled,refund.succeeded,refund.failed';
