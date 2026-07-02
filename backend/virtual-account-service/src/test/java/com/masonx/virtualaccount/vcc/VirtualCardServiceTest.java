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
import com.masonx.virtualaccount.domain.ledger.PostTransaction;
import com.masonx.virtualaccount.domain.po.VaAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import com.masonx.virtualaccount.vcc.dto.CreateVccRequest;
import com.masonx.virtualaccount.vcc.dto.FundVccRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VirtualCardServiceTest {

    private static final String CARD_ID = "card_1";
    private static final String VCC_ACCOUNT_ID = "ac_card";
    private static final String HOLD_ACCOUNT_ID = "ac_card_hold";
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
                Optional.of(vccAccount(BigDecimal.ZERO)));
        when(accountRepo.findByIdForUpdate(HOLD_ACCOUNT_ID)).thenReturn(
                Optional.of(holdAccount(new BigDecimal("25.00"))));
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
                Optional.of(vccAccount(BigDecimal.ZERO)));
        when(accountRepo.findByIdForUpdate(HOLD_ACCOUNT_ID)).thenReturn(
                Optional.of(holdAccount(BigDecimal.ZERO)));
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));

        service.closeCard(CARD_ID, MERCHANT_ID);

        verify(virtualCardRepo).updateStatus(CARD_ID, VirtualCardStatus.CLOSED);
        verify(accountRepo).updateStatus(VCC_ACCOUNT_ID, AccountStatus.CLOSED);
        verify(accountRepo).updateStatus(HOLD_ACCOUNT_ID, AccountStatus.CLOSED);
        verify(ledger, never()).postDirect(any());
    }

    @Test
    void fundCard_uses_stable_idempotency_event_for_retries() {
        when(virtualCardRepo.findById(CARD_ID)).thenReturn(Optional.of(card()));
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));
        when(accountRepo.findById(VCC_ACCOUNT_ID)).thenReturn(
                Optional.of(vccAccount(new BigDecimal("25.00"))));
        when(accountRepo.findById(HOLD_ACCOUNT_ID)).thenReturn(Optional.of(holdAccount(BigDecimal.ZERO)));
        when(idGen.generate(com.masonx.common.id.MasonXIdPrefix.CARD_FUND_TRANSACTION.prefix()))
                .thenReturn("tx_fund_1", "tx_fund_2");
        when(ledger.postIfNew(any(), any(), eq("vcc-card-fund"))).thenReturn(true, false);

        FundVccRequest req = new FundVccRequest(MERCHANT_ID, "client-request-1", new BigDecimal("25.00"));

        service.fundCard(CARD_ID, req);
        service.fundCard(CARD_ID, req);

        ArgumentCaptor<PostTransaction> txCaptor = ArgumentCaptor.forClass(PostTransaction.class);
        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(ledger, times(2)).postIfNew(txCaptor.capture(), eventCaptor.capture(), eq("vcc-card-fund"));
        verify(ledger, never()).postDirect(any());

        assertThat(eventCaptor.getAllValues()).hasSize(2);
        assertThat(eventCaptor.getAllValues().get(0)).isEqualTo(eventCaptor.getAllValues().get(1));
        assertThat(eventCaptor.getAllValues().get(0)).startsWith("vcc_fund_");
        assertThat(txCaptor.getAllValues())
                .allSatisfy(tx -> assertThat(tx.entries())
                        .allSatisfy(entry -> assertThat(entry.sourceEventId())
                                .isEqualTo(eventCaptor.getAllValues().get(0))));
    }

    @Test
    void createCard_creates_primary_and_hold_accounts() {
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));
        when(idGen.generate(com.masonx.common.id.MasonXIdPrefix.VCC_ACCOUNT.prefix()))
                .thenReturn(VCC_ACCOUNT_ID, HOLD_ACCOUNT_ID);
        when(idGen.generate(com.masonx.common.id.MasonXIdPrefix.VIRTUAL_CARD.prefix()))
                .thenReturn(CARD_ID);

        service.createCard(new CreateVccRequest(
                MERCHANT_ID, OWNER_ACCOUNT_ID, "USD", new BigDecimal("100.00"), LocalDate.of(2027, 1, 1)));

        ArgumentCaptor<VaAccount> accountCaptor = ArgumentCaptor.forClass(VaAccount.class);
        verify(accountRepo, times(2)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues())
                .extracting(VaAccount::accountType)
                .containsExactly(AccountType.PREPAID_CARD, AccountType.PREPAID_CARD_HOLD);

        ArgumentCaptor<VirtualCard> cardCaptor = ArgumentCaptor.forClass(VirtualCard.class);
        verify(virtualCardRepo).save(cardCaptor.capture());
        assertThat(cardCaptor.getValue().vccAccountId()).isEqualTo(VCC_ACCOUNT_ID);
        assertThat(cardCaptor.getValue().holdAccountId()).isEqualTo(HOLD_ACCOUNT_ID);
    }

    private static VirtualCard card() {
        return new VirtualCard(
                CARD_ID,
                "999999****1234",
                "999999",
                VCC_ACCOUNT_ID,
                HOLD_ACCOUNT_ID,
                OWNER_ACCOUNT_ID,
                VirtualCardStatus.ACTIVE,
                null,
                "USD",
                LocalDate.of(2027, 1, 1),
                Instant.now(),
                Instant.now());
    }

    private static VaAccount vccAccount(BigDecimal balance) {
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
                AccountStatus.ACTIVE);
    }

    private static VaAccount holdAccount(BigDecimal balance) {
        return new VaAccount(
                HOLD_ACCOUNT_ID,
                Mode.LIVE,
                AccountRole.TENANT,
                "org_1",
                MERCHANT_ID,
                null,
                AccountType.PREPAID_CARD_HOLD,
                "USD",
                AssetClass.FIAT,
                2,
                NormalBalance.DEBIT,
                balance,
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
                AccountStatus.ACTIVE);
    }
}
