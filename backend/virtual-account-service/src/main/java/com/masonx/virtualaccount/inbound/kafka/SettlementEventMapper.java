package com.masonx.virtualaccount.inbound.kafka;

import com.masonx.contracts.settlement.SettlementEvent;
import com.masonx.virtualaccount.domain.constant.AssetClass;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.dto.RecordSettlementCommand;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Anti-corruption layer: translates the wire contract ({@link SettlementEvent})
 * into a VA-native {@link RecordSettlementCommand}. This is the only place that
 * knows the gateway's event vocabulary; the domain never imports contracts.
 *
 * Null-safety: v1 events omit money fields. Defaults applied here:
 *   amount/feeAmount → ZERO, asset → "USD", assetClass → FIAT, scale → 2,
 *   direction → CREDIT. These defaults let v1 events flow without crashing;
 *   the domain handler will reject zero-amount commands before posting.
 */
@Component
public class SettlementEventMapper {

    public RecordSettlementCommand toCommand(SettlementEvent event) {
        BigDecimal amount    = nullSafe(event.amount(),    BigDecimal.ZERO);
        BigDecimal feeAmount = nullSafe(event.feeAmount(), BigDecimal.ZERO);
        BigDecimal netAmount = amount.subtract(feeAmount);

        String     asset      = nullSafe(event.asset(),      "USD");
        AssetClass assetClass = parseAssetClass(event.assetClass());
        int        scale      = event.scale() != null ? event.scale() : 2;
        Direction  direction  = parseDirection(event.direction());

        return new RecordSettlementCommand(
                event.envelope().eventId(),
                event.envelope().tenant(),
                event.paymentId(),
                event.providerRef(),
                amount,
                feeAmount,
                netAmount,
                asset,
                assetClass,
                scale,
                direction);
    }

    private static AssetClass parseAssetClass(String raw) {
        if (raw == null) return AssetClass.FIAT;
        try {
            return AssetClass.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AssetClass.FIAT;
        }
    }

    private static Direction parseDirection(String raw) {
        if (raw == null) return Direction.CREDIT;
        try {
            return Direction.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Direction.CREDIT;
        }
    }

    private static <T> T nullSafe(T value, T defaultValue) {
        return value != null ? value : defaultValue;
    }
}
