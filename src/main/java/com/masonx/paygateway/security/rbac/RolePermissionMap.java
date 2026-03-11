package com.masonx.paygateway.security.rbac;

import com.masonx.paygateway.domain.merchant.MerchantRole;
import java.util.Set;
import java.util.Map;
import java.util.EnumMap;

/**
 * Hardcoded role → permission map for MVP.
 * Migrate to DB-driven in Phase 2.
 *
 * Permission key: "RESOURCE:ACTION"
 */
public final class RolePermissionMap {

    private static final Map<MerchantRole, Set<String>> PERMISSIONS = new EnumMap<>(MerchantRole.class);

    static {
        PERMISSIONS.put(MerchantRole.OWNER, Set.of(
                // PAYMENT
                "PAYMENT:READ", "PAYMENT:CREATE", "PAYMENT:EXECUTE",
                // REFUND
                "REFUND:READ", "REFUND:CREATE", "REFUND:UPDATE", "REFUND:DELETE", "REFUND:EXECUTE",
                // CHARGEBACK
                "CHARGEBACK:READ", "CHARGEBACK:CREATE", "CHARGEBACK:UPDATE", "CHARGEBACK:DELETE", "CHARGEBACK:EXECUTE",
                // API_KEY
                "API_KEY:READ", "API_KEY:CREATE", "API_KEY:UPDATE", "API_KEY:DELETE",
                // WEBHOOK
                "WEBHOOK:READ", "WEBHOOK:CREATE", "WEBHOOK:UPDATE", "WEBHOOK:DELETE",
                // ROUTING_RULE
                "ROUTING_RULE:READ", "ROUTING_RULE:CREATE", "ROUTING_RULE:UPDATE", "ROUTING_RULE:DELETE",
                // LOG
                "LOG:READ",
                // MEMBER
                "MEMBER:READ", "MEMBER:CREATE", "MEMBER:UPDATE", "MEMBER:DELETE",
                // MERCHANT_SETTINGS
                "MERCHANT_SETTINGS:READ", "MERCHANT_SETTINGS:UPDATE", "MERCHANT_SETTINGS:DELETE"
        ));

        PERMISSIONS.put(MerchantRole.ADMIN, Set.of(
                // PAYMENT
                "PAYMENT:READ", "PAYMENT:CREATE", "PAYMENT:EXECUTE",
                // REFUND
                "REFUND:READ", "REFUND:CREATE", "REFUND:UPDATE", "REFUND:DELETE", "REFUND:EXECUTE",
                // CHARGEBACK
                "CHARGEBACK:READ", "CHARGEBACK:CREATE", "CHARGEBACK:UPDATE", "CHARGEBACK:DELETE", "CHARGEBACK:EXECUTE",
                // API_KEY
                "API_KEY:READ", "API_KEY:CREATE", "API_KEY:UPDATE", "API_KEY:DELETE",
                // WEBHOOK
                "WEBHOOK:READ", "WEBHOOK:CREATE", "WEBHOOK:UPDATE", "WEBHOOK:DELETE",
                // ROUTING_RULE
                "ROUTING_RULE:READ", "ROUTING_RULE:CREATE", "ROUTING_RULE:UPDATE", "ROUTING_RULE:DELETE",
                // LOG
                "LOG:READ",
                // MEMBER (no DELETE of merchant itself)
                "MEMBER:READ", "MEMBER:CREATE", "MEMBER:UPDATE", "MEMBER:DELETE",
                // MERCHANT_SETTINGS (no DELETE)
                "MERCHANT_SETTINGS:READ", "MERCHANT_SETTINGS:UPDATE"
        ));

        PERMISSIONS.put(MerchantRole.DEVELOPER, Set.of(
                // PAYMENT - read only
                "PAYMENT:READ",
                // API_KEY
                "API_KEY:READ", "API_KEY:CREATE", "API_KEY:UPDATE", "API_KEY:DELETE",
                // WEBHOOK
                "WEBHOOK:READ", "WEBHOOK:CREATE", "WEBHOOK:UPDATE", "WEBHOOK:DELETE",
                // ROUTING_RULE
                "ROUTING_RULE:READ", "ROUTING_RULE:CREATE", "ROUTING_RULE:UPDATE", "ROUTING_RULE:DELETE",
                // LOG
                "LOG:READ",
                // MERCHANT_SETTINGS - read only
                "MERCHANT_SETTINGS:READ"
        ));

        PERMISSIONS.put(MerchantRole.FINANCE, Set.of(
                // PAYMENT
                "PAYMENT:READ", "PAYMENT:CREATE", "PAYMENT:EXECUTE",
                // REFUND
                "REFUND:READ", "REFUND:CREATE", "REFUND:UPDATE", "REFUND:DELETE", "REFUND:EXECUTE",
                // CHARGEBACK
                "CHARGEBACK:READ", "CHARGEBACK:CREATE", "CHARGEBACK:UPDATE", "CHARGEBACK:DELETE", "CHARGEBACK:EXECUTE",
                // LOG
                "LOG:READ",
                // MERCHANT_SETTINGS - read only
                "MERCHANT_SETTINGS:READ"
        ));

        PERMISSIONS.put(MerchantRole.VIEWER, Set.of(
                "PAYMENT:READ",
                "LOG:READ",
                "MERCHANT_SETTINGS:READ"
        ));
    }

    private RolePermissionMap() {}

    public static boolean allows(MerchantRole role, String resource, String action) {
        Set<String> perms = PERMISSIONS.get(role);
        if (perms == null) return false;
        return perms.contains(resource.toUpperCase() + ":" + action.toUpperCase());
    }

    public static boolean allows(MerchantRole role, Resource resource, Action action) {
        return allows(role, resource.name(), action.name());
    }
}
