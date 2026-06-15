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

    // Access tokens carry the user's token_version. JwtAuthFilter compares this
    // claim with the current DB value, so logout can invalidate older tokens
    // without a Redis blacklist.
    public String generateAccessToken(MerchantUserDetails userDetails) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userDetails.getUserId().toString())
                .claim("email", userDetails.getUsername())
                .claim("type", "MERCHANT_USER")
                .claim("jwtType", "ACCESS")
                .claim("tv", userDetails.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTokenExpiryMs)))
                .signWith(signingKey)
                .compact();
    }

    public int extractTokenVersion(String token) {
        return parseToken(token).get("tv", Integer.class);
    }

    /** Short-lived token (5 min) used only at /auth/mfa/verify — rejected by JwtAuthFilter. */
    public String generateMfaSessionToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("jwtType", "MFA_SESSION")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(5 * 60 * 1000L)))
                .signWith(signingKey)
                .compact();
    }

    public UUID extractUserIdFromMfaToken(String token) {
        Claims claims = parseToken(token);
        if (!"MFA_SESSION".equals(claims.get("jwtType", String.class))) {
            throw new IllegalArgumentException("Not an MFA session token");
        }
        return UUID.fromString(claims.getSubject());
    }

    public boolean isMfaSessionToken(String token) {
        try {
            Claims claims = parseToken(token);
            return "MFA_SESSION".equals(claims.get("jwtType", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
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
