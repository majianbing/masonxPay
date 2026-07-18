package com.masonx.virtualaccount.vcc;

import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.LedgerAccountRole;
import com.masonx.virtualaccount.domain.constant.LedgerAccountStatus;
import com.masonx.virtualaccount.domain.constant.LedgerAccountType;
import com.masonx.virtualaccount.domain.constant.AssetClass;
import com.masonx.virtualaccount.domain.constant.NormalBalance;
import com.masonx.virtualaccount.domain.constant.VirtualCardStatus;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.ledger.posting.VccCloseSweepPostingRule;
import com.masonx.virtualaccount.domain.ledger.posting.VccFundingPostingRule;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
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
import java.util.List;
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
    @Mock LedgerAccountRepository accountRepo;
    @Mock LedgerFacade ledger;
    @Mock SnowflakeIdGenerator idGen;

    VirtualCardService service;

    @BeforeEach
    void setUp() {
        service = new VirtualCardService(virtualCardRepo, accountRepo, ledger, idGen,
                new VccFundingPostingRule(idGen), new VccCloseSweepPostingRule(idGen));
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
        verify(accountRepo, never()).updateStatus(VCC_ACCOUNT_ID, LedgerAccountStatus.CLOSED);
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
        verify(accountRepo).updateStatus(VCC_ACCOUNT_ID, LedgerAccountStatus.CLOSED);
        verify(accountRepo).updateStatus(HOLD_ACCOUNT_ID, LedgerAccountStatus.CLOSED);
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
        when(ledger.postAllIfNew(any(), any(), eq("vcc-card-fund"))).thenReturn(true, false);

        FundVccRequest req = new FundVccRequest(MERCHANT_ID, "client-request-1", new BigDecimal("25.00"));

        service.fundCard(CARD_ID, req);
        service.fundCard(CARD_ID, req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerPostingCommand>> txCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(ledger, times(2)).postAllIfNew(txCaptor.capture(), eventCaptor.capture(), eq("vcc-card-fund"));
        verify(ledger, never()).postDirect(any());

        assertThat(eventCaptor.getAllValues()).hasSize(2);
        assertThat(eventCaptor.getAllValues().get(0)).isEqualTo(eventCaptor.getAllValues().get(1));
        assertThat(eventCaptor.getAllValues().get(0)).startsWith("vcc_fund_");
        assertThat(txCaptor.getAllValues())
                .allSatisfy(commands -> assertThat(commands.get(0).entries())
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

        ArgumentCaptor<LedgerAccount> accountCaptor = ArgumentCaptor.forClass(LedgerAccount.class);
        verify(accountRepo, times(2)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues())
                .extracting(LedgerAccount::ledgerAccountType)
                .containsExactly(LedgerAccountType.PREPAID_CARD, LedgerAccountType.PREPAID_CARD_HOLD);

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

    private static LedgerAccount vccAccount(BigDecimal balance) {
        return new LedgerAccount(
                VCC_ACCOUNT_ID,
                Mode.LIVE,
                LedgerAccountRole.TENANT,
                "org_1",
                MERCHANT_ID,
                null,
                LedgerAccountType.PREPAID_CARD,
                "USD",
                AssetClass.FIAT,
                2,
                NormalBalance.DEBIT,
                balance,
                LedgerAccountStatus.ACTIVE);
    }

    private static LedgerAccount holdAccount(BigDecimal balance) {
        return new LedgerAccount(
                HOLD_ACCOUNT_ID,
                Mode.LIVE,
                LedgerAccountRole.TENANT,
                "org_1",
                MERCHANT_ID,
                null,
                LedgerAccountType.PREPAID_CARD_HOLD,
                "USD",
                AssetClass.FIAT,
                2,
                NormalBalance.DEBIT,
                balance,
                LedgerAccountStatus.ACTIVE);
    }

    private static LedgerAccount ownerAccount() {
        return new LedgerAccount(
                OWNER_ACCOUNT_ID,
                Mode.LIVE,
                LedgerAccountRole.TENANT,
                "org_1",
                MERCHANT_ID,
                null,
                LedgerAccountType.WALLET,
                "USD",
                AssetClass.FIAT,
                2,
                NormalBalance.DEBIT,
                new BigDecimal("100.00"),
                LedgerAccountStatus.ACTIVE);
    }
}
