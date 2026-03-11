package com.masonx.paygateway.security.rbac;

import com.masonx.paygateway.domain.merchant.MerchantUser;
import com.masonx.paygateway.domain.merchant.MerchantUserRepository;
import com.masonx.paygateway.domain.merchant.MerchantUserStatus;
import com.masonx.paygateway.security.MerchantUserDetails;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import java.io.Serializable;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves: current user → MerchantUser role → RolePermissionMap → allow/deny.
 *
 * Usage in @PreAuthorize:
 *   @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
 */
@Component("permissionEvaluator")
public class GatewayPermissionEvaluator implements PermissionEvaluator {

    private final MerchantUserRepository merchantUserRepository;

    public GatewayPermissionEvaluator(MerchantUserRepository merchantUserRepository) {
        this.merchantUserRepository = merchantUserRepository;
    }

    /**
     * @param targetDomainObject the merchantId (UUID)
     * @param permission         "RESOURCE:ACTION" or just "RESOURCE" (not used here — use the overload below)
     */
    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        // Not used — use the targetType/targetId overload via @PreAuthorize SpEL
        return false;
    }

    /**
     * @param targetId   merchantId as UUID or String
     * @param targetType resource name (e.g. "PAYMENT")
     * @param permission action name (e.g. "READ")
     */
    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        if (!(authentication.getPrincipal() instanceof MerchantUserDetails principal)) return false;

        UUID merchantId = toUuid(targetId);
        if (merchantId == null) return false;

        Optional<MerchantUser> membership = merchantUserRepository
                .findByUser_IdAndMerchant_Id(principal.getUserId(), merchantId);

        if (membership.isEmpty()) return false;
        MerchantUser mu = membership.get();
        if (mu.getStatus() != MerchantUserStatus.ACTIVE) return false;

        return RolePermissionMap.allows(mu.getRole(), targetType, permission.toString());
    }

    private UUID toUuid(Serializable id) {
        if (id instanceof UUID uuid) return uuid;
        try {
            return UUID.fromString(id.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
