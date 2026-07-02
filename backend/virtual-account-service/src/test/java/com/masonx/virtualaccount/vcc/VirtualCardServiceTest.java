package com.masonx.virtualaccount.vcc;

import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.AccountRole;
import com.masonx.virtualaccount.domain.constant.AccountStatus;
import com.masonx.virtualaccount.domain.constant.AccountType;
import com.masonx.virtualaccount.domain.constant.AssetClass;
import com.masonx.virtualaccount.domain.constant.NormalBalance;
import com.masonx.virtualaccount.domain.constant.VirtualCardStatus;
import com.masonx.virtualaccount.domain.ledger.AccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.po.VaAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VirtualCardServiceTest {

    private static final String CARD_ID = "card_1";
    private static final String VCC_ACCOUNT_ID = "ac_card";
    private static final String OWNER_ACCOUNT_ID = "ac_owner";
    private static final String MERCHANT_ID = "mer_1";

    @Mock VirtualCardRepository virtualCardRepo;
    @Mock AccountRepository accountRepo;
    @Mock LedgerFacade ledger;
    @Mock SnowflakeIdGenerator idGen;

    VirtualCardService service;

    @BeforeEach
    void setUp() {
        service = new VirtualCardService(virtualCardRepo, accountRepo, ledger, idGen);
    }

    @Test
    void closeCard_rejects_open_authorization_hold() {
        when(virtualCardRepo.findById(CARD_ID)).thenReturn(Optional.of(card()));
        when(accountRepo.findByIdForUpdate(VCC_ACCOUNT_ID)).thenReturn(
                Optional.of(vccAccount(BigDecimal.ZERO, new BigDecimal("25.00"))));
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));

        assertThatThrownBy(() -> service.closeCard(CARD_ID, MERCHANT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("open authorization hold");

        verify(virtualCardRepo, never()).updateStatus(CARD_ID, VirtualCardStatus.CLOSED);
        verify(accountRepo, never()).updateStatus(VCC_ACCOUNT_ID, AccountStatus.CLOSED);
    }

    @Test
    void closeCard_marks_card_and_backing_account_closed_when_no_balance_or_hold() {
        when(virtualCardRepo.findById(CARD_ID)).thenReturn(Optional.of(card()));
        when(accountRepo.findByIdForUpdate(VCC_ACCOUNT_ID)).thenReturn(
                Optional.of(vccAccount(BigDecimal.ZERO, BigDecimal.ZERO)));
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));

        service.closeCard(CARD_ID, MERCHANT_ID);

        verify(virtualCardRepo).updateStatus(CARD_ID, VirtualCardStatus.CLOSED);
        verify(accountRepo).updateStatus(VCC_ACCOUNT_ID, AccountStatus.CLOSED);
        verify(ledger, never()).postDirect(org.mockito.ArgumentMatchers.any());
    }

    private static VirtualCard card() {
        return new VirtualCard(
                CARD_ID,
                "999999****1234",
                "999999",
                VCC_ACCOUNT_ID,
                OWNER_ACCOUNT_ID,
                VirtualCardStatus.ACTIVE,
                null,
                "USD",
                LocalDate.of(2027, 1, 1),
                Instant.now(),
                Instant.now());
    }

    private static VaAccount vccAccount(BigDecimal balance, BigDecimal frozenBalance) {
        return new VaAccount(
                VCC_ACCOUNT_ID,
                Mode.LIVE,
                AccountRole.TENANT,
                "org_1",
                MERCHANT_ID,
                null,
                AccountType.PREPAID_CARD,
                "USD",
                AssetClass.FIAT,
                2,
                NormalBalance.DEBIT,
                balance,
                frozenBalance,
                AccountStatus.ACTIVE);
    }

    private static VaAccount ownerAccount() {
        return new VaAccount(
                OWNER_ACCOUNT_ID,
                Mode.LIVE,
                AccountRole.TENANT,
                "org_1",
                MERCHANT_ID,
                null,
                AccountType.WALLET,
                "USD",
                AssetClass.FIAT,
                2,
                NormalBalance.DEBIT,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                AccountStatus.ACTIVE);
    }
}
