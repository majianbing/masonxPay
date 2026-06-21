package com.masonx.virtualaccount.domain.ledger.validator;

import com.masonx.common.error.BusinessException;
import com.masonx.virtualaccount.domain.ledger.PostTransaction;
import com.masonx.virtualaccount.domain.ledger.validator.api.TransactionValidator;
import org.springframework.stereotype.Component;

/**
 * Rejects transactions that mix assets (e.g. USD entries alongside BTC entries).
 * A double-entry transaction is only meaningful within a single asset denomination;
 * cross-asset transfers must be modelled as two separate transactions linked by a
 * conversion rate, not as a single unbalanced posting.
 */
@Component
public class AssetConsistencyValidator implements TransactionValidator {

    @Override
    public void validate(PostTransaction tx) {
        long distinctAssets = tx.entries().stream()
                .map(e -> e.asset())
                .distinct()
                .count();
        if (distinctAssets > 1) {
            throw new BusinessException(
                    "VA_ASSET_MISMATCH",
                    "All entries in a transaction must share the same asset");
        }
    }
}
