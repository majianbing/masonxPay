package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.merchant.*;
import com.masonx.paygateway.domain.organization.*;
import com.masonx.paygateway.domain.user.*;
import com.masonx.paygateway.security.MerchantUserDetails;
import com.masonx.paygateway.security.jwt.JwtService;
import com.masonx.paygateway.web.dto.AuthResponse;
import com.masonx.paygateway.web.dto.LoginRequest;
import com.masonx.paygateway.web.dto.RegisterRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationUserRepository organizationUserRepository;
    private final MerchantRepository merchantRepository;
    private final MerchantUserRepository merchantUserRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Value("${app.jwt.refresh-token-expiry-ms}")
    private long refreshTokenExpiryMs;

    public AuthService(UserRepository userRepository,
                       OrganizationRepository organizationRepository,
                       OrganizationUserRepository organizationUserRepository,
                       MerchantRepository merchantRepository,
                       MerchantUserRepository merchantUserRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AuthenticationManager authenticationManager) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.organizationUserRepository = organizationUserRepository;
        this.merchantRepository = merchantRepository;
        this.merchantUserRepository = merchantUserRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
    }

    /**
     * Self-register: creates User → Organization → Merchant → OrgUser(ORG_OWNER) → MerchantUser(OWNER)
     */
    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new IllegalArgumentException("Email already registered");
        }

        User user = new User();
        user.setEmail(req.email());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user = userRepository.save(user);

        Organization org = new Organization();
        org.setName(req.merchantName());
        org = organizationRepository.save(org);

        Merchant merchant = new Merchant();
        merchant.setOrganizationId(org.getId());
        merchant.setName(req.merchantName());
        merchant = merchantRepository.save(merchant);

        OrganizationUser orgUser = new OrganizationUser();
        orgUser.setUser(user);
        orgUser.setOrganization(org);
        orgUser.setRole(OrganizationRole.ORG_OWNER);
        orgUser.setStatus("ACTIVE");
        organizationUserRepository.save(orgUser);

        MerchantUser mu = new MerchantUser();
        mu.setUser(user);
        mu.setMerchant(merchant);
        mu.setRole(MerchantRole.OWNER);
        mu.setStatus(MerchantUserStatus.ACTIVE);
        merchantUserRepository.save(mu);

        return issueTokens(user);
    }

    public AuthResponse login(LoginRequest req) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.email(), req.password()));
        MerchantUserDetails principal = (MerchantUserDetails) auth.getPrincipal();
        User user = userRepository.findByEmail(principal.getUsername()).orElseThrow();
        return issueTokens(user);
    }

    public AuthResponse refresh(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

        if (stored.isRevoked() || stored.isExpired()) {
            throw new IllegalArgumentException("Refresh token expired or revoked");
        }

        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        return issueTokens(stored.getUser());
    }

    public void logout(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(t -> {
            t.setRevoked(true);
            refreshTokenRepository.save(t);
        });
    }

    // --- helpers ---

    private AuthResponse issueTokens(User user) {
        MerchantUserDetails details = new MerchantUserDetails(user);
        String accessToken = jwtService.generateAccessToken(details);
        String rawRefresh = generateSecureToken();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(sha256(rawRefresh));
        refreshToken.setExpiresAt(Instant.now().plusMillis(refreshTokenExpiryMs));
        refreshTokenRepository.save(refreshToken);

        List<AuthResponse.OrgMembership> memberships = buildMemberships(user.getId());

        return new AuthResponse(accessToken, rawRefresh, "Bearer", user.getId(), user.getEmail(), memberships);
    }

    public List<AuthResponse.OrgMembership> buildMemberships(UUID userId) {
        return organizationUserRepository.findActiveByUserId(userId).stream()
                .map(ou -> {
                    Organization org = ou.getOrganization();
                    List<AuthResponse.MerchantMembership> merchants =
                            merchantRepository.findAllByOrganizationId(org.getId()).stream()
                                    .flatMap(m -> merchantUserRepository
                                            .findByUser_IdAndMerchant_Id(userId, m.getId())
                                            .filter(mu -> mu.getStatus() == MerchantUserStatus.ACTIVE)
                                            .map(mu -> new AuthResponse.MerchantMembership(
                                                    m.getId(), m.getName(), mu.getRole().name()))
                                            .stream())
                                    .toList();
                    return new AuthResponse.OrgMembership(
                            org.getId(), org.getName(), ou.getRole(), merchants);
                })
                .toList();
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
