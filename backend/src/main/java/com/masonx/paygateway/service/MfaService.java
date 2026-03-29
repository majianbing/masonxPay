package com.masonx.paygateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.user.User;
import com.masonx.paygateway.domain.user.UserRepository;
import com.masonx.paygateway.web.dto.MfaSetupResponse;
import dev.samstevens.totp.code.*;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
@Transactional
public class MfaService {

    private static final int BACKUP_CODE_COUNT = 8;

    private final EncryptionService encryptionService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final CodeVerifier codeVerifier;

    public MfaService(EncryptionService encryptionService,
                      UserRepository userRepository,
                      ObjectMapper objectMapper) {
        this.encryptionService = encryptionService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.codeVerifier = new DefaultCodeVerifier(
                new DefaultCodeGenerator(), new SystemTimeProvider());
    }

    /**
     * Generates a new TOTP secret and QR URI, stores the encrypted secret on the user
     * (not yet active — mfaEnabled remains false until confirmSetup succeeds).
     */
    public MfaSetupResponse generateSetup(User user) {
        String secret = new DefaultSecretGenerator().generate();
        user.setMfaSecret(encryptionService.encrypt(secret));
        userRepository.save(user);

        String qrUri = "otpauth://totp/MasonXPay:" + encode(user.getEmail())
                + "?secret=" + secret
                + "&issuer=MasonXPay"
                + "&algorithm=SHA1&digits=6&period=30";

        return new MfaSetupResponse(secret, qrUri);
    }

    /**
     * Verifies the scanned code, activates MFA, and returns 8 plaintext backup codes
     * (shown once — caller must display them to the user).
     */
    public List<String> confirmSetup(User user, String code) {
        if (user.getMfaSecret() == null) {
            throw new IllegalStateException("MFA setup not initiated — call setup first");
        }
        String secret = encryptionService.decrypt(user.getMfaSecret());
        if (!codeVerifier.isValidCode(secret, code)) {
            throw new IllegalArgumentException("Invalid verification code");
        }

        List<String> plainCodes = generateBackupCodes();
        user.setMfaEnabled(true);
        user.setMfaBackupCodes(toJson(plainCodes.stream().map(this::sha256).toList()));
        userRepository.save(user);

        return plainCodes;
    }

    /**
     * Validates a TOTP code or a backup code for login / disable flows.
     * Consumes the backup code if used (single-use).
     */
    public boolean verify(User user, String code) {
        if (!user.isMfaEnabled() || user.getMfaSecret() == null) return false;
        String secret = encryptionService.decrypt(user.getMfaSecret());

        // Try TOTP first
        if (codeVerifier.isValidCode(secret, code.replace(" ", ""))) return true;

        // Try backup codes
        return consumeBackupCode(user, code);
    }

    /** Disables MFA after verifying a valid code. */
    public void disable(User user, String code) {
        if (!verify(user, code)) {
            throw new IllegalArgumentException("Invalid verification code");
        }
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        user.setMfaBackupCodes(null);
        userRepository.save(user);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean consumeBackupCode(User user, String rawCode) {
        if (user.getMfaBackupCodes() == null) return false;
        String hash = sha256(rawCode.trim().toUpperCase());
        List<String> stored = fromJson(user.getMfaBackupCodes());
        if (!stored.contains(hash)) return false;

        // Remove the used code (single-use)
        List<String> remaining = new ArrayList<>(stored);
        remaining.remove(hash);
        user.setMfaBackupCodes(toJson(remaining));
        userRepository.save(user);
        return true;
    }

    private List<String> generateBackupCodes() {
        SecureRandom random = new SecureRandom();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < BACKUP_CODE_COUNT; i++) {
            byte[] bytes = new byte[4];
            random.nextBytes(bytes);
            String hex = bytesToHex(bytes).toUpperCase();
            codes.add(hex.substring(0, 4) + "-" + hex.substring(4));
        }
        return codes;
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

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String encode(String value) {
        return value.replace("@", "%40").replace("+", "%2B");
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize backup codes", e);
        }
    }

    private List<String> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize backup codes", e);
        }
    }
}
