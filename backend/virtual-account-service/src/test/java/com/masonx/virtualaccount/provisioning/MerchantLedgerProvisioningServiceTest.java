package com.masonx.virtualaccount.provisioning;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.LedgerAccountType;
import com.masonx.virtualaccount.domain.constant.NormalBalance;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.inbound.InboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantLedgerProvisioningServiceTest {

    @Mock LedgerAccountRepository accountRepo;
    @Mock InboxRepository inbox;
    @Mock SnowflakeIdGenerator idGen;

    @Test
    void provisionIfNew_creates_cash_and_wallet_for_merchant() {
        var service = new MerchantLedgerProvisioningService(accountRepo, inbox, idGen);
        when(inbox.markProcessed("evt_1", "merchant.created")).thenReturn(true);
        when(accountRepo.findTenantAccount("mer_1", Mode.TEST, "USD", LedgerAccountType.CASH))
                .thenReturn(Optional.empty());
        when(accountRepo.findTenantAccount("mer_1", Mode.TEST, "USD", LedgerAccountType.WALLET))
                .thenReturn(Optional.empty());
        when(idGen.generate(MasonXIdPrefix.VA_ACCOUNT.prefix())).thenReturn("ac_cash", "ac_wallet");

        boolean processed = service.provisionIfNew(new MerchantProvisioningCommand(
                "evt_1", "org_1", "mer_1", "Acme", List.of(Mode.TEST), "USD"));

        assertThat(processed).isTrue();
        var captor = ArgumentCaptor.forClass(LedgerAccount.class);
        verify(accountRepo, org.mockito.Mockito.times(2)).saveIfAbsent(captor.capture());
        assertThat(captor.getAllValues())
                .anySatisfy(account -> {
                    assertThat(account.ledgerAccountType()).isEqualTo(LedgerAccountType.CASH);
                    assertThat(account.normalBalance()).isEqualTo(NormalBalance.DEBIT);
                    assertThat(account.merchantId()).isEqualTo("mer_1");
                })
                .anySatisfy(account -> {
                    assertThat(account.ledgerAccountType()).isEqualTo(LedgerAccountType.WALLET);
                    assertThat(account.normalBalance()).isEqualTo(NormalBalance.CREDIT);
                    assertThat(account.orgId()).isEqualTo("org_1");
                });
    }

    @Test
    void provisionIfNew_skips_duplicate_event() {
        var service = new MerchantLedgerProvisioningService(accountRepo, inbox, idGen);
        when(inbox.markProcessed("evt_1", "merchant.created")).thenReturn(false);

        boolean processed = service.provisionIfNew(new MerchantProvisioningCommand(
                "evt_1", "org_1", "mer_1", "Acme", List.of(Mode.TEST), "USD"));

        assertThat(processed).isFalse();
        verify(accountRepo, never()).saveIfAbsent(any());
    }
}
