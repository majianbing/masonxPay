package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.merchant.*;
import com.masonx.paygateway.domain.organization.*;
import com.masonx.paygateway.domain.user.*;
import com.masonx.paygateway.security.MerchantUserDetails;
import com.masonx.paygateway.security.jwt.JwtService;
import com.masonx.paygateway.web.dto.AuthResponse;
import com.masonx.paygateway.web.dto.LoginRequest;
import com.masonx.paygateway.web.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock OrganizationUserRepository organizationUserRepository;
    @Mock MerchantRepository merchantRepository;
    @Mock MerchantUserRepository merchantUserRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock AuthenticationManager authenticationManager;
    @Mock MfaService mfaService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository, organizationRepository, organizationUserRepository,
                merchantRepository, merchantUserRepository, refreshTokenRepository,
                passwordEncoder, jwtService, authenticationManager, mfaService);
        // inject @Value field
        ReflectionTestUtils.setField(authService, "refreshTokenExpiryMs", 86_400_000L);
    }

    private User activeUser(UUID id, String email) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        u.setEmail(email);
        u.setPasswordHash("$2a$hash");
        return u;
    }

    // ── register ─────────────────────────────────────────────────────────────

    @Test
    void register_duplicateEmail_throwsIllegalArgument() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() ->
                authService.register(new RegisterRequest("dup@example.com", "pass", "Acme")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void register_newUser_createsHierarchyAndReturnsTokens() {
        UUID userId = UUID.randomUUID();
        User user = activeUser(userId, "new@example.com");

        Organization org = new Organization();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());
        org.setName("Acme");

        Merchant merchant = new Merchant();
        ReflectionTestUtils.setField(merchant, "id", UUID.randomUUID());
        merchant.setName("Acme");

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("pass")).thenReturn("$2a$hashed");
        when(userRepository.save(any())).thenReturn(user);
        when(organizationRepository.save(any())).thenReturn(org);
        when(merchantRepository.save(any())).thenReturn(merchant);
        when(jwtService.generateAccessToken(any())).thenReturn("access-token");
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(organizationUserRepository.findActiveByUserId(userId)).thenReturn(List.of());

        AuthResponse resp = authService.register(
                new RegisterRequest("new@example.com", "pass", "Acme"));

        assertThat(resp.accessToken()).isEqualTo("access-token");
        assertThat(resp.userId()).isEqualTo(userId);
        assertThat(resp.email()).isEqualTo("new@example.com");
        assertThat(resp.mfaRequired()).isFalse();

        verify(organizationUserRepository).save(any());
        verify(merchantUserRepository).save(any());
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    void login_mfaEnabled_returnsMfaPending() {
        UUID userId = UUID.randomUUID();
        User user = activeUser(userId, "mfa@example.com");
        user.setMfaEnabled(true);

        MerchantUserDetails details = new MerchantUserDetails(user);
        Authentication auth = new UsernamePasswordAuthenticationToken(details, null, List.of());

        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(userRepository.findByEmail("mfa@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateMfaSessionToken(userId)).thenReturn("mfa-session-token");

        AuthResponse resp = authService.login(new LoginRequest("mfa@example.com", "pass"));

        assertThat(resp.mfaRequired()).isTrue();
        assertThat(resp.mfaSessionToken()).isEqualTo("mfa-session-token");
        assertThat(resp.accessToken()).isNull();
    }

    @Test
    void login_mfaDisabled_returnsFullTokens() {
        UUID userId = UUID.randomUUID();
        User user = activeUser(userId, "noMfa@example.com");

        MerchantUserDetails details = new MerchantUserDetails(user);
        Authentication auth = new UsernamePasswordAuthenticationToken(details, null, List.of());

        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(userRepository.findByEmail("noMfa@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(any())).thenReturn("access-token");
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(organizationUserRepository.findActiveByUserId(userId)).thenReturn(List.of());

        AuthResponse resp = authService.login(new LoginRequest("noMfa@example.com", "pass"));

        assertThat(resp.mfaRequired()).isFalse();
        assertThat(resp.accessToken()).isEqualTo("access-token");
    }

    @Test
    void login_wrongPassword_propagatesException() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() ->
                authService.login(new LoginRequest("user@example.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    void refresh_revokedToken_throws() {
        UUID userId = UUID.randomUUID();
        User user = activeUser(userId, "r@example.com");

        RefreshToken rt = new RefreshToken();
        rt.setUser(user);
        rt.setRevoked(true);
        rt.setExpiresAt(Instant.now().plusSeconds(3600));

        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(rt));

        assertThatThrownBy(() -> authService.refresh("some-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired or revoked");
    }

    @Test
    void refresh_expiredToken_throws() {
        UUID userId = UUID.randomUUID();
        User user = activeUser(userId, "e@example.com");

        RefreshToken rt = new RefreshToken();
        rt.setUser(user);
        rt.setRevoked(false);
        rt.setExpiresAt(Instant.now().minusSeconds(1)); // already expired

        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(rt));

        assertThatThrownBy(() -> authService.refresh("some-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired or revoked");
    }

    @Test
    void refresh_validToken_rotatesAndIssuesNewTokens() {
        UUID userId = UUID.randomUUID();
        User user = activeUser(userId, "valid@example.com");

        RefreshToken rt = new RefreshToken();
        rt.setUser(user);
        rt.setRevoked(false);
        rt.setExpiresAt(Instant.now().plusSeconds(3600));

        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(rt));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.generateAccessToken(any())).thenReturn("new-access-token");
        when(organizationUserRepository.findActiveByUserId(userId)).thenReturn(List.of());

        AuthResponse resp = authService.refresh("valid-raw-token");

        assertThat(resp.accessToken()).isEqualTo("new-access-token");
        assertThat(rt.isRevoked()).isTrue(); // old token was revoked
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_revokesRefreshToken() {
        UUID userId = UUID.randomUUID();
        User user = activeUser(userId, "logout@example.com");

        RefreshToken rt = new RefreshToken();
        rt.setUser(user);
        rt.setRevoked(false);
        rt.setExpiresAt(Instant.now().plusSeconds(3600));

        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(rt));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        authService.logout("raw-token");

        assertThat(rt.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(rt);
    }

    @Test
    void logout_unknownToken_doesNotThrow() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());
        assertThatCode(() -> authService.logout("unknown-token")).doesNotThrowAnyException();
    }
}
