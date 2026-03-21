package com.masonx.paygateway.security.jwt;

import com.masonx.paygateway.security.MerchantUserDetails;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTokenExpiryMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-expiry-ms}") long accessTokenExpiryMs) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiryMs = accessTokenExpiryMs;
    }

    // TODO: Immediate token invalidation (e.g. forced logout, compromised key) is not supported
    //   without a Redis blacklist or equivalent. This is a deliberate trade-off: we avoid Redis
    //   to keep the stack simple and cheap — Postgres handles idempotency, distributed locking,
    //   and single-source-of-truth for all financial state. Refresh token rotation in the DB
    //   already limits the blast radius (attacker can't get a new access token), and the 24 h
    //   access-token expiry is the remaining window.
    //
    //   If immediate invalidation ever becomes a requirement, the cheapest path that stays
    //   Redis-free is a `token_version` (integer) column on the `users` table:
    //     1. Embed token_version as a JWT claim on issuance.
    //     2. In parseToken / isValid, load the user's current token_version from DB and
    //        reject any token whose claim is lower than the stored value.
    //     3. Logout / revoke increments token_version — all previously issued tokens for
    //        that user instantly fail, with no cache needed.
    //   The trade-off is one extra DB read per authenticated request; at the transaction
    //   volumes this gateway targets (≤ 100 k/day per merchant) that cost is negligible.
    public String generateAccessToken(MerchantUserDetails userDetails) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userDetails.getUserId().toString())
                .claim("email", userDetails.getUsername())
                .claim("type", "MERCHANT_USER")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTokenExpiryMs)))
                .signWith(signingKey)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(parseToken(token).getSubject());
    }

    public String extractEmail(String token) {
        return parseToken(token).get("email", String.class);
    }

    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
