package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.user.User;
import com.masonx.paygateway.domain.user.UserRepository;
import com.masonx.paygateway.security.MerchantUserDetails;
import com.masonx.paygateway.service.AuthService;
import com.masonx.paygateway.service.MfaService;
import com.masonx.paygateway.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final MfaService mfaService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, MfaService mfaService, UserRepository userRepository) {
        this.authService = authService;
        this.mfaService = mfaService;
        this.userRepository = userRepository;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(req));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return ResponseEntity.ok(authService.refresh(req.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest req) {
        authService.logout(req.refreshToken());
        return ResponseEntity.noContent().build();
    }

    // ── MFA endpoints ─────────────────────────────────────────────────────────

    /** Step 2 of login: exchange MFA session token + TOTP code for full auth tokens. */
    @PostMapping("/mfa/verify")
    public ResponseEntity<AuthResponse> mfaVerify(@Valid @RequestBody MfaVerifyRequest req) {
        return ResponseEntity.ok(authService.verifyMfa(req.mfaSessionToken(), req.code()));
    }

    /** Initiate MFA setup: generates a secret and QR URI (MFA not yet active). */
    @PostMapping("/mfa/setup")
    public ResponseEntity<MfaSetupResponse> mfaSetup(
            @AuthenticationPrincipal MerchantUserDetails principal) {
        User user = loadUser(principal);
        return ResponseEntity.ok(mfaService.generateSetup(user));
    }

    /**
     * Confirm MFA setup: verifies the scanned code and activates MFA.
     * Returns 8 single-use backup codes — shown once, not recoverable.
     */
    @PostMapping("/mfa/confirm")
    public ResponseEntity<Map<String, List<String>>> mfaConfirm(
            @AuthenticationPrincipal MerchantUserDetails principal,
            @Valid @RequestBody MfaConfirmRequest req) {
        User user = loadUser(principal);
        List<String> backupCodes = mfaService.confirmSetup(user, req.code());
        return ResponseEntity.ok(Map.of("backupCodes", backupCodes));
    }

    /** Disable MFA — requires a valid TOTP or backup code. */
    @DeleteMapping("/mfa")
    public ResponseEntity<Void> mfaDisable(
            @AuthenticationPrincipal MerchantUserDetails principal,
            @Valid @RequestBody MfaDisableRequest req) {
        User user = loadUser(principal);
        mfaService.disable(user, req.code());
        return ResponseEntity.noContent().build();
    }

    /** Returns whether MFA is currently enabled for the authenticated user. */
    @GetMapping("/mfa/status")
    public ResponseEntity<Map<String, Boolean>> mfaStatus(
            @AuthenticationPrincipal MerchantUserDetails principal) {
        User user = loadUser(principal);
        return ResponseEntity.ok(Map.of("mfaEnabled", user.isMfaEnabled()));
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private User loadUser(MerchantUserDetails principal) {
        return userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    }
}
