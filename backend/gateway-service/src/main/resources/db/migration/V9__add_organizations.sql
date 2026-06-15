-- 1. Create organizations table
CREATE TABLE organizations (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 2. Add organization_id to merchants (nullable for migration)
ALTER TABLE merchants ADD COLUMN organization_id UUID REFERENCES organizations(id);

-- 3. Create one organization per existing merchant and link them
DO $$
DECLARE
    m           RECORD;
    new_org_id  UUID;
BEGIN
    FOR m IN SELECT * FROM merchants LOOP
        INSERT INTO organizations(id, name, created_at, updated_at)
        VALUES (gen_random_uuid(), m.name, m.created_at, m.created_at)
        RETURNING id INTO new_org_id;

        UPDATE merchants SET organization_id = new_org_id WHERE id = m.id;
    END LOOP;
END $$;

-- 4. Now enforce NOT NULL
ALTER TABLE merchants ALTER COLUMN organization_id SET NOT NULL;

-- 5. Create organization_users table
CREATE TABLE organization_users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID        NOT NULL REFERENCES users(id),
    organization_id UUID        NOT NULL REFERENCES organizations(id),
    role            VARCHAR(20) NOT NULL DEFAULT 'ORG_MEMBER',
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, organization_id)
);

-- 6. Seed organization_users from existing merchant_users
--    OWNER -> ORG_OWNER, ADMIN -> ORG_ADMIN, rest -> ORG_MEMBER
INSERT INTO organization_users(id, user_id, organization_id, role, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    mu.user_id,
    m.organization_id,
    CASE mu.role
        WHEN 'OWNER' THEN 'ORG_OWNER'
        WHEN 'ADMIN' THEN 'ORG_ADMIN'
        ELSE 'ORG_MEMBER'
    END,
    CASE mu.status WHEN 'ACTIVE' THEN 'ACTIVE' ELSE 'PENDING_INVITE' END,
    mu.created_at,
    mu.updated_at
FROM merchant_users mu
JOIN merchants m ON mu.merchant_id = m.id
ON CONFLICT (user_id, organization_id) DO NOTHING;

-- 7. Indexes
CREATE INDEX idx_organizations_status       ON organizations(status);
CREATE INDEX idx_merchants_org              ON merchants(organization_id);
CREATE INDEX idx_org_users_user             ON organization_users(user_id);
CREATE INDEX idx_org_users_org              ON organization_users(organization_id);
