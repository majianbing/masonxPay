CREATE TABLE route_policies (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    mode VARCHAR(10) NOT NULL,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    policy_version INT NOT NULL DEFAULT 1,
    description TEXT,
    created_by_user_id UUID,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_route_policies_active_merchant_mode
    ON route_policies (merchant_id, mode)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_route_policies_merchant_mode_updated
    ON route_policies (merchant_id, mode, updated_at DESC);

CREATE TABLE route_policy_routes (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    route_order INT NOT NULL,
    name VARCHAR(120) NOT NULL,
    default_route BOOLEAN NOT NULL DEFAULT FALSE,
    conditions_json TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_route_policy_routes_policy_order UNIQUE (policy_id, route_order)
);

CREATE UNIQUE INDEX uq_route_policy_routes_default
    ON route_policy_routes (policy_id)
    WHERE default_route = TRUE;

CREATE INDEX idx_route_policy_routes_policy_order
    ON route_policy_routes (merchant_id, policy_id, route_order);

CREATE TABLE route_policy_steps (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    route_id UUID NOT NULL,
    step_order INT NOT NULL,
    provider_account_id UUID NOT NULL,
    traffic_weight INT NOT NULL DEFAULT 100,
    max_cost_bps INT,
    skip_if_degraded BOOLEAN NOT NULL DEFAULT TRUE,
    outcome_actions_json TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_route_policy_steps_route_order UNIQUE (route_id, step_order),
    CONSTRAINT chk_route_policy_steps_weight CHECK (traffic_weight > 0 AND traffic_weight <= 100),
    CONSTRAINT chk_route_policy_steps_max_cost CHECK (max_cost_bps IS NULL OR max_cost_bps >= 0)
);

CREATE INDEX idx_route_policy_steps_policy
    ON route_policy_steps (merchant_id, policy_id, route_id, step_order);

CREATE INDEX idx_route_policy_steps_provider_account
    ON route_policy_steps (merchant_id, provider_account_id);
