# Security Policy

MasonXPay is a self-hosted, open-source payment gateway. The operator who deploys it is responsible for the security posture of their deployment. This document describes the security architecture of the software, known trade-offs, deployment requirements, and the process for reporting vulnerabilities.

This software is not a PCI-certified product or service. It is infrastructure you deploy to build a payment-capable system. PCI DSS compliance obligations belong to the operator (see [PCI DSS Guidance](#pci-dss-guidance-for-operators)).

---

## Table of Contents

1. [Reporting a Vulnerability](#reporting-a-vulnerability)
2. [Supported Versions](#supported-versions)
3. [Cryptographic Design](#cryptographic-design)
4. [Known Security Trade-offs](#known-security-trade-offs)
5. [Authentication and Authorization](#authentication-and-authorization)
6. [PCI DSS Guidance for Operators](#pci-dss-guidance-for-operators)
7. [Deployment Security Checklist](#deployment-security-checklist)
8. [Security Architecture Reference](#security-architecture-reference)
9. [Dependency and Supply Chain](#dependency-and-supply-chain)

---

## Reporting a Vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

Use [GitHub's private security advisory feature](../../security/advisories/new) to report issues confidentially. You can also email the maintainer directly (see the GitHub profile for contact details).

### What to include in your report

- A clear description of the vulnerability and its impact
- Steps to reproduce or a proof of concept
- The version or commit hash you tested against
- Whether you believe this is exploitable in a default deployment

### Response timeline

| Stage | Target |
|---|---|
| Acknowledgement | Within 72 hours |
| Initial triage and severity assessment | Within 7 days |
| Fix for critical issues | Within 90 days |
| Fix for medium / low issues | Within 180 days |

Confirmed vulnerabilities will be assigned a CVE through GitHub's CVE numbering authority. A public GitHub Security Advisory will be published after the patch is released. Reporters will be credited unless they request anonymity.

### Scope

**In scope:**
- Authentication bypass or privilege escalation across merchant tenants
- Unauthorized access to payment data or provider credentials
- Cryptographic weaknesses in key handling or token generation
- SQL injection or other injection vulnerabilities
- Secrets leaked in API responses, error messages, or logs
- SSRF via webhook endpoint URL validation (webhook endpoints accept arbitrary URLs — SSRF to internal services is a real risk)
- Race conditions enabling double-charging or payment duplication

**Out of scope:**
- Vulnerabilities in upstream providers (Stripe, Square, Braintree) — report to the respective provider
- Issues requiring physical access to the database or host
- Theoretical vulnerabilities without a working proof of concept
- Missing rate limiting on non-payment endpoints
- Vulnerabilities that require the attacker to already have OWNER-level access

---

## Supported Versions

Security fixes are applied to the `master` branch and tagged releases. Forks and older releases do not receive backported patches. Operators should track the latest release.

---

## Cryptographic Design

### Provider credential and MFA secret encryption

All sensitive secrets stored in the database are encrypted using **AES-256-GCM**.

- **Algorithm:** AES/GCM/NoPadding
- **IV:** 12 bytes, randomly generated per encryption operation via `SecureRandom`
- **Authentication tag:** 128 bits
- **Storage format:** `base64(iv):base64(ciphertext+authTag)` stored as a TEXT column
- **Key material:** Single symmetric key loaded from the `ENCRYPTION_KEY` environment variable (base64-encoded 32-byte value). Generate with `openssl rand -base64 32`.

Applies to: Stripe secret keys, Square access tokens, Braintree private keys, TOTP MFA secrets.

> **Key rotation warning:** There is no key versioning or automatic re-encryption migration. If `ENCRYPTION_KEY` is changed, all encrypted records become unreadable. Operators must perform a full re-encryption migration before rotating the key, and must keep the original key available in their break-glass procedure to restore from backup.

### API key hashing

- **Secret keys (`sk_*`):** SHA-256 hashed before storage. The raw value is returned exactly once at creation and is never stored or retrievable. An attacker with read access to the database cannot recover secret keys.
- **Publishable keys (`pk_*`):** Stored in plaintext — they are public identifiers by design and are safe to expose in frontend code.
- **Key format:** `sk_live_{48 hex chars}` or `sk_test_{48 hex chars}` (24 bytes from `SecureRandom`).

### Refresh tokens

- 32 bytes from `SecureRandom`, URL-safe base64-encoded
- Stored as SHA-256 hash in the `refresh_tokens` table
- **Rotated on every use:** the consumed token is revoked before a new one is issued, preventing replay
- 7-day expiry enforced in the database

### Invite tokens

- 32 bytes from `SecureRandom`, URL-safe base64-encoded
- SHA-256 hashed in storage; 48-hour expiry; enforced as single-use via a `used` column

### Webhook signing

Stripe-compatible HMAC-SHA256 format:

```
t={unix_timestamp},v1={hex(HMAC-SHA256(secret, "{timestamp}.{body}"))}
```

Consumers should verify both the signature and the timestamp, and reject events where the timestamp is more than 5 minutes old (matching Stripe's own webhook security guidance).

### Password hashing

BCrypt via Spring Security's `BCryptPasswordEncoder` (default cost factor 10).

### JWT signing

- Algorithm: HMAC-SHA256 (`HS256`)
- Key derived from `JWT_SECRET` via `Keys.hmacShaKeyFor`
- **Access token:** 24-hour expiry; claims include `sub` (userId), `email`, `type=MERCHANT_USER`, `jwtType=ACCESS`
- **MFA session token:** 5-minute expiry; `jwtType=MFA_SESSION`. The `JwtAuthFilter` explicitly rejects MFA session tokens for all endpoints except `/auth/mfa/verify`.

### Sensitive field redaction in logs

`ApiRequestLoggingFilter` stores API request and response bodies in `gateway_logs`. Before writing, a regex pass replaces the values of the following JSON fields with `"[REDACTED]"`:

`password`, `passwordHash`, `secretKey`, `accessToken`, `privateKey`, `publicKey`, `refreshToken`, `mfaSecret`, `mfaBackupCodes`, `mfaSessionToken`, `code`, `btPrivateKey`, `btPublicKey`

`Authorization` and `X-Publishable-Key` request headers are also redacted.

---

## Known Security Trade-offs

These are intentional design decisions with documented risk. Operators should include them in their risk register.

### 1. Access tokens cannot be revoked before expiry

After logout, the refresh token is immediately revoked in the database. However, any issued access token remains valid until its 24-hour expiry, because there is no token blacklist (no Redis is used by design).

**Impact:** A stolen access token grants up to 24 hours of unauthorized access after the legitimate user logs out or discovers the compromise.

**Mitigation options for operators:**
- Reduce the access token TTL via `app.jwt.access-token-expiry-ms` (e.g., 15–60 minutes for higher-security deployments)
- The codebase includes a `TODO` comment in `JwtService` describing a `token_version` column approach that would enable immediate invalidation with a single DB read per request — this can be implemented without Redis

### 2. Webhook delivery is not atomic with the database write

Payment state transitions are written to the database, and then a webhook event is published via Spring's `ApplicationEventPublisher`. These two operations are not wrapped in a single atomic transaction (no transactional outbox pattern).

**Impact:** If the JVM crashes between the database save and the event publish, the payment state is persisted but the webhook is silently lost.

**Accepted trade-off:** At the transaction volumes this system targets, the crash window is narrow and the operational cost of a full outbox table outweighs the risk. The `TODO` comment in `PaymentIntentService.publishEvent()` describes the outbox migration path.

**Mitigation for operators:** Monitor `webhook_deliveries` for records stuck in `PENDING` or `FAILED` state and set up alerting when all retries are exhausted.

### 3. Single symmetric encryption key — no envelope encryption

All provider secrets and MFA data are encrypted with one `ENCRYPTION_KEY`. Compromise of this key decrypts everything.

**Mitigation for operators:** Store `ENCRYPTION_KEY` in a secret manager that supports envelope encryption (AWS Secrets Manager, HashiCorp Vault). The application retrieves the value at startup; the key manager wraps it with a managed master key.

### 4. Rate limiting is per-JVM instance

Alibaba Sentinel rate limits (60 req/min for payment intent creation, 30 req/min for confirm) are enforced within a single process. If multiple backend instances are running horizontally, each has independent counters — effective throughput is `limit × instance_count`.

**Mitigation for operators running multiple instances:** Place a shared rate limiter (API gateway, nginx `limit_req`, or Sentinel with Nacos as the rule source) in front of the cluster. The Nacos migration path is documented in `SentinelConfig.java`.

### 5. PostgreSQL port exposed in the default Docker Compose file

The default `docker-compose.yml` maps port 5432 to the host for local development convenience. This must not be used in production.

---

## Authentication and Authorization

### Authentication layers

| Request type | Mechanism | Storage |
|---|---|---|
| Payment API (create PI, confirm, etc.) | `Authorization: Bearer sk_xxx` | SHA-256 hash in `api_keys` |
| Dashboard API (JWT users) | `Authorization: Bearer <jwt>` | Stateless — verified by signature |
| Publishable key (checkout session, tokenize) | `X-Publishable-Key: pk_xxx` | Plaintext in `api_keys` |
| Webhook ingress (Stripe/Square/Braintree) | Provider-specific signature | Signing secret per endpoint |

Public endpoints that require no authentication: `/api/v1/auth/**`, `/api/v1/invites/**`, `/api/v1/providers/*/webhook`, `/pub/**`, `/actuator/health`.

### RBAC

Five roles per merchant: `OWNER > ADMIN > DEVELOPER | FINANCE > VIEWER`

Permissions are enforced via `@PreAuthorize` with a custom `GatewayPermissionEvaluator`. Role membership is always resolved from the database per request — it is never embedded in JWT claims — so revocation takes effect immediately without token invalidation.

### MFA

Optional TOTP-based second factor (RFC 6238 — SHA1, 6 digits, 30-second period). Compatible with Google Authenticator, Microsoft Authenticator, and Authy.

- TOTP secret: AES-256-GCM encrypted before storage
- Backup codes: 8 codes in `XXXX-XXXX` format, SHA-256 hashed, single-use
- Login flow: credentials → `MFA_SESSION` token (5 min, usable only at `/auth/mfa/verify`) → TOTP code → full access + refresh tokens

### Card data architecture

MasonXPay never handles raw card numbers, CVVs, or full track data. In all integration patterns:

- The browser SDK communicates directly with the upstream provider (Stripe, Square, Braintree) to tokenize card data.
- MasonXPay receives only provider tokens (`pm_xxx`, `nonce_xxx`, etc.).
- The `gateway_logs` table stores API request and response bodies with sensitive field redaction, but these bodies never contain card numbers because the tokenization happens client-side.

This is a foundational constraint for understanding PCI scope.

---

## PCI DSS Guidance for Operators

> **Disclaimer:** This section is informational only. It does not constitute legal or compliance advice. Engage a Qualified Security Assessor (QSA) to determine the PCI DSS scope and requirements applicable to your specific deployment.

MasonXPay acts as a payment orchestrator, not a card data vault. Raw cardholder data is tokenized by the upstream provider before any value reaches MasonXPay servers.

### Requirements most relevant to this deployment

| PCI DSS Requirement | Relevance | Notes |
|---|---|---|
| Req 2 — Secure configurations | High | Never deploy with default placeholder secrets (`change-me-in-production`, `Password123!`) |
| Req 3 — Protect stored account data | High | MasonXPay does not store PANs or CVVs. Provider credentials are AES-256-GCM encrypted at rest. |
| Req 4 — Protect data in transit | High | Operators must provision TLS termination. The default Docker Compose and standalone CloudFormation template do NOT include TLS. |
| Req 6 — Develop secure systems | Medium | Monitor Spring Boot, Java 21, and key library CVEs. Rebuild and redeploy promptly. |
| Req 7 — Restrict access | Medium | Use the minimum required role. VIEWER for read-only users; avoid over-provisioning OWNER/ADMIN. |
| Req 8 — Identify users | Medium | Each user has an individual account. Enable MFA for OWNER and ADMIN roles. |
| Req 10 — Log and monitor | Medium | `gateway_logs` captures all `/api/v1/` traffic with redaction. Integrate with a SIEM. Default 2-year retention (configurable via `LOG_RETENTION_PERIODS`). |
| Req 12 — Security policy | Low | This document is a starting point. Operators must maintain their own security policies. |

### SAQ guidance

- **Hosted pay-link only (no embedded form):** Merchants may qualify for **SAQ A** since all cardholder data entry is handled entirely within the upstream provider's scope.
- **Embedded checkout form:** Consult your acquirer or QSA. The scope depends on how the provider's JavaScript is loaded and whether your pages are in scope.
- **Operator of the MasonXPay infrastructure:** Likely subject to **SAQ D** or a full ROC assessment depending on transaction volume and network architecture. The self-hosted nature of the gateway means the operator's server infrastructure is in scope.

---

## Deployment Security Checklist

Complete this checklist before handling any live payment data.

### Secrets (critical — must do before go-live)

- [ ] Generate a unique `JWT_SECRET` with `openssl rand -base64 32`. The application enforces a minimum 256-bit key at startup.
- [ ] Generate a unique `ENCRYPTION_KEY` with `openssl rand -base64 32`. Must decode to exactly 32 bytes; the application throws at startup if not.
- [ ] Generate a strong `DB_PASSWORD`. Never use the placeholder from `docker-compose.yml`.
- [ ] Store all secrets in a secret manager (AWS Secrets Manager, HashiCorp Vault, or equivalent). Never commit them to source control.
- [ ] Document `ENCRYPTION_KEY` in a break-glass procedure in your secure vault. Losing this key renders all encrypted provider credentials and MFA secrets permanently unreadable, including database backups.
- [ ] Use distinct secrets for each environment (development, staging, production). Never share keys across environments.

### Network hardening

- [ ] Remove `ports: - "5432:5432"` from Docker Compose. The database must not be reachable from outside the container network.
- [ ] Place TLS termination (nginx, Caddy, AWS ALB + ACM) in front of both the backend (port 8080) and the dashboard (port 3000). Minimum TLS 1.2; TLS 1.3 preferred.
- [ ] Set `CORS_ALLOWED_ORIGINS` to the exact production dashboard origin. Do not use a wildcard (`*`) in production.
- [ ] For the standalone CloudFormation template: the default security group opens SSH (port 22) to `0.0.0.0/0`. Restrict this to your known IP range before or immediately after deployment.
- [ ] For the managed CloudFormation template: verify the RDS security group does not allow public ingress on port 5432.

### Access control

- [ ] Register the first admin user immediately after deployment to prevent unauthorized self-registration.
- [ ] Enable MFA for all users with OWNER or ADMIN roles.
- [ ] Use `DEVELOPER` or `FINANCE` for service accounts; `VIEWER` for read-only monitoring access.
- [ ] Rotate all API keys immediately if there is any suspicion of compromise. Revoking a secret key also revokes the paired publishable key.

### Provider credentials

- [ ] Use test-mode keys (`sk_test_*`) in all non-production environments.
- [ ] Rotate provider API keys on a quarterly schedule.
- [ ] Revoke provider keys immediately if a secret key appears in logs, error output, or source control.

### Monitoring and alerting

- [ ] Aggregate `gateway_logs` to a SIEM or log management system.
- [ ] Alert on sustained Sentinel rate-limit events (HTTP 429 from payment intent endpoints) — these may indicate API abuse.
- [ ] Alert on webhook delivery failures: monitor `webhook_deliveries` for records stuck in `FAILED` state with no more retries.
- [ ] Subscribe to CVE feeds for: `spring-boot-3.2.x`, `spring-security-6.x`, `jjwt-0.12.x`, `postgresql-jdbc`, `braintree-java`, `stripe-java`, `eclipse-temurin:21`.

### Backups

- [ ] Enable automated PostgreSQL backups before going live.
- [ ] Test a restore procedure that uses the same `ENCRYPTION_KEY` — restoring the database without the matching key makes all encrypted data unreadable.
- [ ] Verify backup encryption at rest (the backup file itself should be encrypted independently of the database contents).

---

## Security Architecture Reference

Quick reference for auditors, threat modelers, and security reviewers.

| Control | Implementation | Limitation |
|---|---|---|
| Provider credential encryption | AES-256-GCM, random IV per record | Single key; no envelope encryption or key versioning |
| TOTP secret encryption | AES-256-GCM, same key | Same key rotation risk as above |
| Password hashing | BCrypt (cost factor 10) | — |
| API secret key storage | SHA-256 hash; raw shown once | — |
| Refresh token storage | SHA-256 hash, rotated on use, 7-day TTL | — |
| Invite token storage | SHA-256 hash, single-use, 48-hour TTL | — |
| JWT signing | HMAC-SHA256; 24h access / 5min MFA session | Access tokens not revocable before expiry |
| Webhook signing | HMAC-SHA256, Stripe-compatible | Consumers must verify timestamp themselves |
| Sensitive field redaction | Regex on 14 field names, request + response | New endpoints with new sensitive fields must extend `SENSITIVE_FIELDS` |
| Rate limiting | Alibaba Sentinel, in-process | Per-JVM; not shared across scaled instances |
| Session management | Stateless JWT | No server-side session; no blacklist |
| MFA | TOTP (RFC 6238); 8 single-use backup codes | Optional; must be enrolled by the user |
| Tenant isolation | `merchant_id` filter on every DB query | Verified at the service layer; never inferred from JWT |
| RBAC | 5 roles; DB-resolved per request | Roles not cached; immediate revocation on membership change |
| Log retention | 6-month time partitions; default 4 periods (2 years) | Configurable via `LOG_RETENTION_PERIODS` |

---

## Dependency and Supply Chain

The project uses Maven for dependency management. To enumerate the full transitive dependency tree:

```bash
mvn dependency:tree
```

### Key dependencies to monitor for CVEs

| Dependency | Version | Security relevance |
|---|---|---|
| `spring-boot-starter-parent` | 3.2.x | Transitive security fixes (Spring Security, Tomcat) |
| `spring-security` | (managed by Boot) | Authentication and authorization core |
| `jjwt-api / jjwt-impl` | 0.12.5 | JWT signing and verification |
| `dev.samstevens.totp` | 1.7.1 | TOTP code generation and verification |
| `postgresql` JDBC | (managed by Boot) | Database driver |
| `stripe-java` | 24.3.0 | Stripe API client |
| `braintree-java` | 3.19.0 | Braintree API client |
| `sentinel-core` | 1.8.9 | Rate limiting |

### Docker base images

The production Docker image uses `eclipse-temurin:21-jre-alpine`. Monitor for CVEs in this base image and rebuild when JRE security patches are released.

### Recommendations for forks and self-hosted deployments

- Enable **Dependabot** on your fork to receive automated dependency update PRs.
- Pin production builds to a specific Git tag or commit SHA, not a branch.
- Use **GitHub's dependency review action** in your CI pipeline to block PRs that introduce dependencies with known critical CVEs.
