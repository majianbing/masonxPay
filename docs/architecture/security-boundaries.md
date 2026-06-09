# Security Boundaries

MasonXPay is a multi-tenant payment operations platform. Security boundaries are part of the domain model, not optional middleware.

## Tenant And Mode Scope

Every tenant-owned table must include `merchant_id`, and every read/write path must enforce merchant scope. Controllers, services, repositories, dashboard query keys, background jobs, and tests should all make the tenant boundary explicit.

TEST/LIVE mode isolation is a second boundary, not a replacement for tenant isolation. Resources that can exist in both environments must be scoped by both `merchant_id` and mode. This includes connectors, customers, payment links, subscriptions, invoices, retries, payment instruments, and dashboard list/detail queries.

## User Realms

Merchant portal users and platform admin users stay separate. Do not reuse merchant user tables for platform operators, and do not embed membership or role authority in JWTs when immediate revocation requires database lookup.

## Provider And Webhook Security

Provider credentials are secrets and must be encrypted at rest. Provider webhooks are unauthenticated at the HTTP edge, so each webhook controller must verify the provider signature or equivalent provider-authenticated fetch before reconciling state.

Request logs, gateway logs, outbox events, Kafka events, read models, dashboard payloads, and AI evidence must not include secrets, provider credentials, raw provider payloads, signature headers, private keys, refresh tokens, MFA secrets, PAN, CVV, or track data.

## PCI Boundary

Raw PAN, track data, and CVV must never enter MasonXPay core services. Browser/provider SDKs collect sensitive card data, and MasonXPay stores only opaque provider, vault, wallet, local-payment, or future network-token references plus safe metadata.

If MasonXPay ever handles card data directly, that work must live in a separately deployed, isolated PCI-scoped component. The payment core should still receive only opaque instrument references and safe metadata.

## AI Data Boundary

AI is outside the payment execution authority path. External model providers are outside the MasonXPay trust boundary by default. Model-bound evidence must be redacted, aggregated, and allowlisted by workflow stage.

AI may analyze, explain, recommend, and draft. AI may not authorize, decline, route, mutate routing rules directly, bypass tenant/RBAC checks, or receive secrets, raw payment payloads, card data, webhook signatures, provider credentials, private keys, tokens, or unredacted customer PII.

## References

- [Payment core](payment-core.md)
- [Routing and orchestration](routing-orchestration.md)
- [AI control plane](ai-control-plane.md)
- [AI-assisted operations control plane plan](../planning/ai-control-plane-plan.md)
