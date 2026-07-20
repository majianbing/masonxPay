package com.masonx.virtualaccount.provisioning;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.AssetClass;
import com.masonx.virtualaccount.domain.constant.LedgerAccountRole;
import com.masonx.virtualaccount.domain.constant.LedgerAccountStatus;
import com.masonx.virtualaccount.domain.constant.LedgerAccountType;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.inbound.InboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class MerchantLedgerProvisioningService {

    private static final String EVENT_TYPE = "merchant.created";
    private static final List<LedgerAccountType> DEFAULT_ACCOUNT_TYPES =
            List.of(LedgerAccountType.CASH, LedgerAccountType.WALLET);

    private final LedgerAccountRepository accountRepo;
    private final InboxRepository inbox;
    private final SnowflakeIdGenerator idGen;

    public MerchantLedgerProvisioningService(LedgerAccountRepository accountRepo,
                                             InboxRepository inbox,
                                             SnowflakeIdGenerator idGen) {
        this.accountRepo = accountRepo;
        this.inbox = inbox;
        this.idGen = idGen;
    }

    @Transactional
    public boolean provisionIfNew(MerchantProvisioningCommand command) {
        if (!inbox.markProcessed(command.eventId(), EVENT_TYPE)) {
            return false;
        }
        provision(command);
        return true;
    }

    @Transactional
    public void provision(MerchantProvisioningCommand command) {
        List<Mode> modes = command.modes() == null || command.modes().isEmpty()
                ? List.of(Mode.TEST)
                : command.modes();
        String asset = command.asset() == null || command.asset().isBlank()
                ? "USD"
                : command.asset().toUpperCase();

        for (Mode mode : modes) {
            for (LedgerAccountType type : DEFAULT_ACCOUNT_TYPES) {
                ensureAccount(command.organizationId(), command.merchantId(), mode, asset, type);
            }
        }
    }

    private void ensureAccount(String organizationId,
                               String merchantId,
                               Mode mode,
                               String asset,
                               LedgerAccountType type) {
        if (accountRepo.findTenantAccount(merchantId, mode, asset, type).isPresent()) {
            return;
        }
        accountRepo.saveIfAbsent(new LedgerAccount(
                idGen.generate(MasonXIdPrefix.VA_ACCOUNT.prefix()),
                mode,
                LedgerAccountRole.TENANT,
                organizationId,
                merchantId,
                null,
                type,
                asset,
                AssetClass.FIAT,
                scale(asset),
                LedgerAccount.normalBalanceFor(type),
                LedgerAccount.classify(type),
                BigDecimal.ZERO,
                LedgerAccountStatus.ACTIVE));
    }

    private int scale(String asset) {
        return switch (asset.toUpperCase()) {
            case "JPY", "KRW", "CLP", "VND" -> 0;
            default -> 2;
        };
    }
}
