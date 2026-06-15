# Backend Guide

Backend code is Java 21, Spring Boot, Maven, and package root `com.masonx.paygateway`.

## Style

- Use 4-space indentation.
- Prefer constructor injection.
- Keep DTOs at API boundaries.
- Keep business logic out of controllers.
- Add comments only when they clarify ownership, intent, or non-obvious behavior.
- Avoid broad `catch (Exception)` blocks unless there is a clear fallback and logging strategy.
- Surface client-facing errors as a typed `BusinessException` mapped to a safe status and message; never let original exception details or stack traces reach the client. The global exception handler must redact any unhandled exception to a generic message.
- New-module Redis keys are namespaced with the module prefix (e.g. `va:`); existing keys keep the current `mxp:` namespace. Never use a bare, unprefixed Redis key.
- New modules namespace their tables with a per-module prefix (e.g. `va_` for the Virtual Account module) so table ownership is identifiable and the module stays cleanly separable. Existing payment-core and orchestration tables are grandfathered and keep their current unprefixed names.

## Module Boundaries

The backend is a modular monolith. Payment/refund state transitions, provider adapters, routing, webhook delivery, outbox/Kafka workers, projections, Redis hot path, identity/access, billing, and dashboard/API entrypoints each own one concern.

Cross-module calls should go through services/interfaces or outbox events. Do not shortcut into another module's repositories or internals.

## Transaction Rules

Do not put remote provider calls inside database transactions. Use short transactions around state validation, locks, idempotency records, provider-reference persistence, and outbox writes.

Every money-moving operation must have:

- an idempotent database state check before execution
- a deterministic provider idempotency key derived from stable identifiers

See [payment core](../architecture/payment-core.md) for the full invariant set.

## User Realms

Merchant portal users and platform admin users are separate realms.

| Realm | Tables |
|---|---|
| Merchant portal | `users`, `merchant_users` |
| Platform admin | `admin_users`, `admin_audit_logs` |

Do not share tables between the realms.

## RBAC

- Roles: `OWNER > ADMIN > DEVELOPER | FINANCE > VIEWER`
- Use permission checks such as `@PreAuthorize("@permissionEvaluator.hasPermission(auth, #merchantId, 'RESOURCE', 'ACTION')")`.
- Resolve membership from the database, not JWT role claims, so revocation takes effect immediately.

## Security Implementations

| Concern | Approach |
|---|---|
| Provider credentials | AES-256-GCM encrypted in DB via `EncryptionService` |
| Refresh tokens | SHA-256 hashed in DB and rotated on each use |
| Invite tokens | SHA-256 hashed, 48h expiry, single-use |
| Webhook payloads | Provider-specific signature verification |
| TOTP MFA secret | AES-256-GCM encrypted in DB |
| MFA backup codes | SHA-256 hashed and single-use |
| Gateway logs | Sensitive JSON fields redacted before DB write |
