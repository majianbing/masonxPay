CREATE TABLE IF NOT EXISTS subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    interval_unit VARCHAR(20) NOT NULL,
    interval_count INTEGER NOT NULL,
    current_period_start TIMESTAMPTZ,
    current_period_end TIMESTAMPTZ,
    trial_ends_at TIMESTAMPTZ,
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
    canceled_at TIMESTAMPTZ,
    metadata_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_subscriptions_merchant_created
    ON subscriptions (merchant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_subscriptions_merchant_customer
    ON subscriptions (merchant_id, customer_id, created_at DESC);

CREATE TABLE IF NOT EXISTS subscription_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    subscription_id UUID NOT NULL,
    description TEXT NOT NULL,
    amount BIGINT NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_subscription_items_subscription
    ON subscription_items (merchant_id, subscription_id);

CREATE TABLE IF NOT EXISTS subscription_checkout_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    subscription_id UUID NOT NULL,
    token VARCHAR(120) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_subscription_checkout_links_subscription
    ON subscription_checkout_links (merchant_id, subscription_id, created_at DESC);
