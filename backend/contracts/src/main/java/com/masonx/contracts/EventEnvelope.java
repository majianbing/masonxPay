package com.masonx.contracts;

import com.masonx.common.tenant.TenantRef;

import java.time.Instant;
import java.util.UUID;

/**
 * Standard metadata wrapper for every cross-service event: identity, versioning,
 * causality, and tenant scope (org + merchant + mode via {@link TenantRef}).
 *
 * Boundary rules:
 * <ul>
 *   <li>No sensitive data — never carry PAN/CVV/raw provider payloads.</li>
 *   <li>Additive-only — add optional fields; never remove/rename/retype. Bump
 *       {@code schemaVersion} on an additive change.</li>
 * </ul>
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String correlationId,
        TenantRef tenant
) {
}
