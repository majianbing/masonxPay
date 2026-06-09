# Auth And Security Implementation

This file covers implementation-level auth and security behavior. Durable security boundaries live in [security boundaries](../architecture/security-boundaries.md).

## MFA

MasonXPay supports optional TOTP MFA for merchant portal users.

Login flow:

```text
POST /api/v1/auth/login
  -> MFA disabled: full AuthResponse
  -> MFA enabled: { mfaRequired: true, mfaSessionToken: <5-min JWT> }

POST /api/v1/auth/mfa/verify
  -> validates TOTP or backup code
  -> full AuthResponse
```

MFA session token rules:

- short-lived JWT, 5 minutes
- claim `jwtType: "MFA_SESSION"`
- rejected by `JwtAuthFilter` as a bearer access token
- accepted only at `POST /auth/mfa/verify`

MFA endpoints:

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/mfa/verify` | Public | Exchange session token and TOTP code for full tokens |
| `POST` | `/mfa/setup` | JWT | Generate encrypted secret and QR URI |
| `POST` | `/mfa/confirm` | JWT | Activate MFA and return backup codes |
| `DELETE` | `/mfa` | JWT | Disable MFA with live code |
| `GET` | `/mfa/status` | JWT | Return MFA status |

Backup codes are generated as `XXXX-XXXX` hex strings, SHA-256 hashed before storage, and single-use.

## Webhook Security Rules

Provider webhook controllers are `permitAll()` by design. Signature verification is the security boundary.

Mandatory rules:

- Reject if the webhook secret/signature key is not configured.
- Reject if the provider signature header is absent.
- Do not keep an unverified fallback path.
- Use constant-time comparison such as `MessageDigest.isEqual()` for HMAC comparisons.
- Never log raw webhook bodies or signature headers.

Provider signature details:

| Provider | Header | Algorithm | Input |
|---|---|---|---|
| Stripe | `Stripe-Signature` | Stripe SDK verification | SDK handles internally |
| Braintree | `bt_signature` form param | Braintree SDK parse | SDK handles internally |
| Square | `x-square-hmacsha256-signature` | HMAC-SHA256 | `notificationUrl + rawBody`, Base64 |

Controller skeleton:

```java
if (secret == null || secret.isBlank()) {
    log.warn("Xxx webhook received but secret not configured, rejecting");
    return ResponseEntity.badRequest().build();
}
if (signatureHeader == null || signatureHeader.isBlank()) {
    log.warn("Xxx webhook missing signature header");
    return ResponseEntity.badRequest().build();
}
// Verify provider-specific signature.
// Reconcile only if verification passed.
```

## Logging Security Rules

`ApiRequestLoggingFilter` stores API request/response bodies in `gateway_logs`. Sensitive fields must be redacted before DB writes.

Current sensitive field examples:

- `password`
- `passwordHash`
- `secretKey`
- `accessToken`
- `privateKey`
- `publicKey`
- `refreshToken`
- `mfaSecret`
- `mfaBackupCodes`
- `mfaSessionToken`
- `code`
- `btPrivateKey`
- `btPublicKey`

When adding new provider credentials or auth tokens, add them to the sensitive field set.

Do not log raw webhook bodies, signature headers, raw provider responses, or provider error response bodies.
