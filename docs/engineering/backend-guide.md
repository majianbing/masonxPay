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

## Package Organization

Each module owns one root package (e.g. `com.masonx.paygateway`, `com.masonx.virtualaccount`). Within that root, split by concern using the sub-packages below. Add a sub-package when a group of files shares a clear, single responsibility — not to give every individual class its own home.

### Standard sub-packages

| Sub-package | What belongs there |
|---|---|
| `constant/` | Enums and constants shared across the module. Keeps them out of the root so domain types aren't drowned by flag values. |
| `po/` | Persistent objects — Java records or classes that map 1-to-1 to a database row. No business logic. |
| `dto/` | Data transfer objects used at integration or API boundaries (inbound commands, outbound responses). Create this sub-package only once there are two or more distinct DTOs; a single class can live at the module root or next to its handler. |
| `api/` | Interfaces that define the public contract of the module — what other modules or inbound adapters call. Do not put implementations here. |
| `<context>/` | A bounded-context sub-package (e.g. `ledger/`, `routing/`) that groups all files belonging to one cohesive domain concept: repositories, services, value objects, and internal interfaces. Each context can have its own `validator/` or other sub-packages as needed. |
| `validator/` | Chain-of-responsibility validator implementations inside a bounded context. Put the validator interfaces in the parent package (or in `<context>/`) — not in a nested `validator/api/` — unless those interfaces are explicitly consumed by a different module. |
| `inbound/` | Entry-point adapters: Kafka consumers, scheduled jobs, REST controllers that delegate to domain services. |
| `config/` | Spring `@Configuration` and `@Bean` factory classes. |

### Rules

- **Interfaces live next to their primary user.** A `TransactionValidator` interface used only by `LedgerPostingService` belongs in `ledger/` or `ledger/validator/`, not in a nested `validator/api/` sub-package. Reserve the `api/` package for contracts consumed across context or module boundaries.
- **No stranded files at the module root.** If a class or interface belongs to a specific sub-package by the rules above, move it there. Files lingering at the root after a reorganization are a sign the split is incomplete.
- **Don't create a sub-package for one class.** A package earns its place when it groups two or more files with a shared, nameable role. A lone class in its own package adds navigation cost with no organizational benefit.
- **Depth limit: three levels below the module root.** `ledger/validator/` is fine; `ledger/validator/impl/api/` is not. Deep nesting signals that the abstraction is being over-engineered.

### Example layout (Virtual Account module)

```
com.masonx.virtualaccount
  constant/           ← LedgerAccountStatus, Direction, NormalBalance, …
  api/                ← SettlementHandler (consumed by inbound adapters)
  dto/                ← RecordSettlementCommand (grows here as more arrive)
  po/                 ← LedgerAccount, LedgerEntry
  ledger/             ← bounded context: repos, service, value objects
    posting/          ← posting rules that translate business events to LedgerPostingCommand
    validator/        ← AssetConsistencyValidator, NetZeroValidator, …
                         TransactionValidator and EntryValidator interfaces live here too
  inbound/
    kafka/            ← SettlementEventConsumer, SettlementEventMapper
  config/             ← IdConfig
```

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
