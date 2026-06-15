package com.masonx.virtualaccount.domain;

import com.masonx.common.tenant.TenantRef;

import java.util.UUID;

/**
 * VA-native command produced by the inbound ACL from a settlement event. The
 * domain depends only on this and on the shared kernel ({@code common}) — never
 * on the wire contracts.
 *
 * Money fields (amount, asset/currency, direction, fees) are added with the
 * ledger domain design; see {@code docs/engineering/virtual-account-guide.md}.
 */
public record RecordSettlementCommand(
        String sourceEventId,
        TenantRef tenant,
        UUID paymentId,
        String providerRef
) {
}
