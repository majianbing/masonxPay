package com.masonx.paygateway.security.jwt;

import com.masonx.paygateway.domain.user.User;
import com.masonx.paygateway.security.MerchantUserDetails;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class JwtServiceTest {

    private static final String SECRET =
            "test-secret-key-that-is-at-least-256-bits-long-for-testing-purposes";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 3_600_000L); // 1 h
    }

    private MerchantUserDetails buildDetails(UUID userId) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setEmail("alice@example.com");
        user.setPasswordHash("$2a$hash");
        return new MerchantUserDetails(user);
    }

    @Test
    void accessToken_isValid() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(buildDetails(userId));
        assertThat(jwtService.isValid(token)).isTrue();
    }

    @Test
    void accessToken_extractsUserId() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(buildDetails(userId));
        assertThat(jwtService.extractUserId(token)).isEqualTo(userId);
    }

    @Test
    void accessToken_extractsEmail() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(buildDetails(userId));
        assertThat(jwtService.extractEmail(token)).isEqualTo("alice@example.com");
    }

    @Test
    void accessToken_isNotMfaSessionToken() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(buildDetails(userId));
        assertThat(jwtService.isMfaSessionToken(token)).isFalse();
    }

    @Test
    void mfaSessionToken_isMfaToken() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateMfaSessionToken(userId);
        assertThat(jwtService.isMfaSessionToken(token)).isTrue();
    }

    @Test
    void mfaSessionToken_extractsCorrectUserId() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateMfaSessionToken(userId);
        assertThat(jwtService.extractUserIdFromMfaToken(token)).isEqualTo(userId);
    }

    @Test
    void extractUserIdFromMfaToken_withAccessToken_throws() {
        UUID userId = UUID.randomUUID();
        String accessToken = jwtService.generateAccessToken(buildDetails(userId));
        assertThatThrownBy(() -> jwtService.extractUserIdFromMfaToken(accessToken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MFA session token");
    }

    @Test
    void expiredToken_isInvalid() {
        // Token with -1ms expiry is already expired at creation
        JwtService shortLived = new JwtService(SECRET, -1L);
        UUID userId = UUID.randomUUID();
        String token = shortLived.generateAccessToken(buildDetails(userId));
        assertThat(jwtService.isValid(token)).isFalse();
    }

    @Test
    void tamperedToken_isInvalid() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(buildDetails(userId));
        String tampered = token.substring(0, token.length() - 4) + "XXXX";
        assertThat(jwtService.isValid(tampered)).isFalse();
    }
}
