package com.masonx.virtualaccount.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class InternalTokenFilterTest {

    private final InternalTokenFilter filter = new InternalTokenFilter("secret");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void internal_path_requires_valid_token() throws ServletException, IOException {
        MockHttpServletResponse response = doFilter("/internal/ledger/trial-balance", null);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void va_account_v1_path_accepts_valid_internal_token() throws ServletException, IOException {
        MockHttpServletResponse response = doFilter("/v1/va/accounts/ac_1", "secret");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_INTERNAL");
    }

    @Test
    void ledger_v1_path_accepts_valid_internal_token() throws ServletException, IOException {
        MockHttpServletResponse response = doFilter("/v1/ledger/accounts/ac_1/entries", "secret");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_INTERNAL");
    }

    @Test
    void public_path_does_not_require_token() throws ServletException, IOException {
        MockHttpServletResponse response = doFilter("/actuator/health", null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private MockHttpServletResponse doFilter(String uri, String token) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (token != null) {
            request.addHeader("X-Internal-Token", token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
