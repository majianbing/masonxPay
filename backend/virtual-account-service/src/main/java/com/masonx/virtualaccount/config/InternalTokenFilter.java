package com.masonx.virtualaccount.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates service-to-service requests using a shared secret header.
 *
 * <p>Callers (e.g. rail-simulator) must send {@code X-Internal-Token: <configured-secret>}.
 * Missing or wrong tokens cause a 401 before the request reaches the controller.
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final String TOKEN_HEADER = "X-Internal-Token";

    private final String expectedToken;

    public InternalTokenFilter(@Value("${va.internal.auth-token}") String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        if (requiresInternalAuth(request.getRequestURI())) {
            String token = request.getHeader(TOKEN_HEADER);
            if (!expectedToken.equals(token)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                        "Missing or invalid " + TOKEN_HEADER);
                return;
            }
            var auth = new UsernamePasswordAuthenticationToken(
                    "rail-simulator", null,
                    List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        filterChain.doFilter(request, response);
    }

    private boolean requiresInternalAuth(String uri) {
        return uri.startsWith("/internal/")
                || uri.startsWith("/v1/va/accounts")
                || uri.startsWith("/v1/ledger/");
    }
}
