package com.masonx.contracts;

import com.masonx.common.tenant.TenantRef;

import java.time.Instant;

/**
 * Standard metadata wrapper for every cross-service event: identity, versioning,
 * causality, and tenant scope (org + merchant + mode via {@link TenantRef}).
 *
 * <p>{@code eventId} is a prefixed snowflake id ({@code evt_{snowflakeId}}),
 * minted by the producer — not a UUID. {@code correlationId} is free-form for
 * tracing.
 *
 * Boundary rules:
 * <ul>
 *   <li>No sensitive data — never carry PAN/CVV/raw provider payloads.</li>
 *   <li>Additive-only — add optional fields; never remove/rename/retype. Bump
 *       {@code schemaVersion} on an additive change.</li>
 * </ul>
 */
public record EventEnvelope(
        String eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String correlationId,
        TenantRef tenant
) {
}
