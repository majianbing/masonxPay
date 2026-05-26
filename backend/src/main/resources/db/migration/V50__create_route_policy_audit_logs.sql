CREATE TABLE route_policy_audit_logs (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    action VARCHAR(40) NOT NULL,
    actor_user_id UUID,
    before_status VARCHAR(20),
    after_status VARCHAR(20),
    before_state TEXT,
    after_state TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_route_policy_audit_logs_policy
    ON route_policy_audit_logs (merchant_id, policy_id, created_at DESC);
