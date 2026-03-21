package com.masonx.paygateway.security;

import com.masonx.paygateway.domain.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Spring Security principal for Merchant Portal users.
 * Roles/permissions are NOT stored here — they're resolved per-merchant from DB
 * via GatewayPermissionEvaluator to ensure revocation takes effect immediately.
 */
public class MerchantUserDetails implements UserDetails {

    private final UUID userId;
    private final String email;
    private final String passwordHash;
    private final boolean active;

    public MerchantUserDetails(User user) {
        this.userId = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.active = user.getStatus().name().equals("ACTIVE");
    }

    public UUID getUserId() { return userId; }

    @Override
    public String getUsername() { return email; }

    @Override
    public String getPassword() { return passwordHash; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(); // permissions resolved dynamically per merchant
    }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return active; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return active; }
}
