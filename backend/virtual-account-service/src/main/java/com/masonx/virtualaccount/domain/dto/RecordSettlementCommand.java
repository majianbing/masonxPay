package com.masonx.virtualaccount.domain.dto;

import com.masonx.common.tenant.TenantRef;
import com.masonx.virtualaccount.domain.constant.AssetClass;
import com.masonx.virtualaccount.domain.constant.Direction;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * VA-native command produced by the inbound ACL from a settlement event. The
 * domain depends only on this and on the shared kernel ({@code common}) — never
 * on the wire contracts. All money fields are VA-typed (enums, BigDecimal).
 *
 * @param amount     Gross settlement amount. Always positive.
 * @param feeAmount  Platform fee. Zero if no fee applies.
 * @param netAmount  amount − feeAmount. What the merchant actually receives.
 * @param asset      Asset code: "USD", "BTC", etc.
 * @param assetClass FIAT or CRYPTO.
 * @param scale      Decimal precision for this asset.
 * @param direction  CREDIT = money in for merchant; DEBIT = money out (refund reversal).
 */
public record RecordSettlementCommand(
        String sourceEventId,
        TenantRef tenant,
        UUID paymentId,
        String providerRef,
        BigDecimal amount,
        BigDecimal feeAmount,
        BigDecimal netAmount,
        String asset,
        AssetClass assetClass,
        int scale,
        Direction direction
) {
}
