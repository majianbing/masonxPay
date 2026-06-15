package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.merchant.*;
import com.masonx.paygateway.domain.user.User;
import com.masonx.paygateway.domain.user.UserRepository;
import com.masonx.paygateway.web.dto.AcceptInviteRequest;
import com.masonx.paygateway.web.dto.InviteInfoResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

@Service
@Transactional
public class InviteService {

    private final InviteTokenRepository inviteTokenRepository;
    private final MerchantUserRepository merchantUserRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;

    @Value("${app.invite.token-expiry-ms}")
    private long inviteTokenExpiryMs;

    @Value("${app.invite.base-url}")
    private String baseUrl;

    public InviteService(InviteTokenRepository inviteTokenRepository,
                         MerchantUserRepository merchantUserRepository,
                         UserRepository userRepository,
                         PasswordEncoder passwordEncoder,
                         JavaMailSender mailSender) {
        this.inviteTokenRepository = inviteTokenRepository;
        this.merchantUserRepository = merchantUserRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
    }

    public void sendInvite(MerchantUser merchantUser, Merchant merchant, User inviter) {
        String rawToken = generateSecureToken();

        InviteToken inviteToken = new InviteToken();
        inviteToken.setMerchantUser(merchantUser);
        inviteToken.setTokenHash(sha256(rawToken));
        inviteToken.setExpiresAt(Instant.now().plusMillis(inviteTokenExpiryMs));
        inviteTokenRepository.save(inviteToken);

        String inviteLink = baseUrl + "/api/v1/invites/" + rawToken + "/accept";
        sendInviteEmail(merchantUser.getUser().getEmail(), inviter.getEmail(),
                merchant.getName(), inviteLink);
    }

    @Transactional(readOnly = true)
    public InviteInfoResponse getInviteInfo(String rawToken) {
        InviteToken inviteToken = resolveToken(rawToken);
        MerchantUser mu = inviteToken.getMerchantUser();
        return new InviteInfoResponse(
                mu.getInvitedBy() != null ? mu.getInvitedBy().getEmail() : null,
                mu.getMerchant().getName(),
                mu.getUser().getEmail(),
                mu.getRole(),
                mu.getMerchant().getId()
        );
    }

    public void acceptInvite(String rawToken, AcceptInviteRequest req) {
        InviteToken inviteToken = resolveToken(rawToken);
        MerchantUser mu = inviteToken.getMerchantUser();
        User user = mu.getUser();

        // If user was a placeholder (no real password set), set their password
        if ("INVITE_PENDING".equals(user.getPasswordHash())) {
            if (req.password() == null || req.password().isBlank()) {
                throw new IllegalArgumentException("Password required for new users");
            }
            user.setPasswordHash(passwordEncoder.encode(req.password()));
            userRepository.save(user);
        }

        mu.setStatus(MerchantUserStatus.ACTIVE);
        merchantUserRepository.save(mu);

        inviteToken.setUsed(true);
        inviteTokenRepository.save(inviteToken);
    }

    private InviteToken resolveToken(String rawToken) {
        String hash = sha256(rawToken);
        InviteToken token = inviteTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid invite token"));
        if (token.isUsed()) throw new IllegalStateException("Invite already used");
        if (token.isExpired()) throw new IllegalStateException("Invite token expired");
        return token;
    }

    private void sendInviteEmail(String toEmail, String inviterEmail,
                                  String merchantName, String inviteLink) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setTo(toEmail);
        msg.setSubject("You've been invited to " + merchantName);
        msg.setText(String.format(
                "%s has invited you to join %s on Pay.MasonX.\n\nAccept your invite here:\n%s\n\nThis link expires in 48 hours.",
                inviterEmail, merchantName, inviteLink));
        mailSender.send(msg);
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
