package com.masonx.paygateway.security.apikey;

import com.masonx.paygateway.domain.apikey.ApiKey;
import com.masonx.paygateway.domain.apikey.ApiKeyRepository;
import com.masonx.paygateway.domain.apikey.ApiKeyStatus;
import com.masonx.paygateway.domain.apikey.ApiKeyType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Authenticates requests carrying an API key.
 * Handles two patterns:
 *   - Authorization: Bearer sk_test_xxx  (server-side SECRET key)
 *   - X-Publishable-Key: pk_test_xxx     (browser PUBLISHABLE key)
 *
 * Runs before JwtAuthFilter. If an API key is detected and valid, authentication
 * is set and the JWT filter will skip (SecurityContext already populated).
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyAuthFilter(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Check Authorization: Bearer sk_* / pk_*
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (token.startsWith("sk_") || token.startsWith("pk_")) {
                authenticate(token);
                filterChain.doFilter(request, response);
                return;
            }
        }

        // Check X-Publishable-Key: pk_*
        String publishableKey = request.getHeader("X-Publishable-Key");
        if (publishableKey != null && publishableKey.startsWith("pk_")) {
            authenticate(publishableKey);
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(String rawKey) {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            return;
        }
        String hash = sha256(rawKey);
        Optional<ApiKey> keyOpt = apiKeyRepository.findByKeyHash(hash);
        if (keyOpt.isEmpty()) return;

        ApiKey key = keyOpt.get();
        if (key.getStatus() != ApiKeyStatus.ACTIVE) return;

        // Publishable keys cannot be used as Bearer secret keys (only via X-Publishable-Key)
        if (rawKey.startsWith("pk_") && !rawKey.equals(rawKey)) return;

        ApiKeyAuthentication auth = new ApiKeyAuthentication(
                key.getId(), key.getMerchantId(), key.getMode(), key.getType());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
