package com.masonx.virtualaccount.vcc;

import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.CardCreateRequestRepository;
import com.masonx.virtualaccount.domain.CardCreateRequestRepository.CardCreateRequest;
import com.masonx.virtualaccount.domain.CardControlProfileRepository;
import com.masonx.virtualaccount.domain.CardIssuerReconciliationRepository;
import com.masonx.virtualaccount.domain.CardProgramRepository;
import com.masonx.virtualaccount.domain.CardholderRepository;
import com.masonx.virtualaccount.domain.IssuerPartnerRepository;
import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.ledger.posting.VccCloseSweepPostingRule;
import com.masonx.virtualaccount.domain.ledger.posting.VccFundingPostingRule;
import com.masonx.virtualaccount.domain.ledger.posting.VccWithdrawPostingRule;
import com.masonx.virtualaccount.domain.po.CardProgram;
import com.masonx.virtualaccount.domain.po.Cardholder;
import com.masonx.virtualaccount.domain.po.IssuerPartner;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessment;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessmentLine;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessmentSnapshot;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import com.masonx.virtualaccount.fee.AssessPrepaidFeeCommand;
import com.masonx.virtualaccount.fee.PrepaidFeeAssessmentService;
import com.masonx.virtualaccount.fee.PrepaidFeePostingService;
import com.masonx.virtualaccount.issuer.CreateIssuerCardCommand;
import com.masonx.virtualaccount.issuer.CreateIssuerCardResult;
import com.masonx.virtualaccount.issuer.IssuerCardProviderDispatcher;
import com.masonx.virtualaccount.issuer.IssuerCardProviderService;
import com.masonx.virtualaccount.issuer.IssuerCardStatus;
import com.masonx.virtualaccount.vcc.dto.CreateVccRequest;
import com.masonx.virtualaccount.vcc.dto.FundVccRequest;
import com.masonx.virtualaccount.vcc.dto.WithdrawVccRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
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
    private static final String PROGRAM_ID = "cprog_1";
    private static final String ISSUER_PARTNER_ID = "ip_1";
    private static final String CARDHOLDER_ID = "ch_1";
    private static final String CREATE_KEY = "client-create-1";

    @Mock VirtualCardRepository virtualCardRepo;
    @Mock CardProgramRepository cardProgramRepo;
    @Mock CardControlProfileRepository cardControlProfileRepo;
    @Mock CardCreateRequestRepository cardCreateRequestRepo;
    @Mock CardIssuerReconciliationRepository cardIssuerReconciliationRepo;
    @Mock CardholderRepository cardholderRepo;
    @Mock IssuerPartnerRepository issuerPartnerRepo;
    @Mock LedgerAccountRepository accountRepo;
    @Mock LedgerFacade ledger;
    @Mock IssuerCardProviderDispatcher issuerCards;
    @Mock IssuerCardProviderService issuerCardProvider;
    @Mock SnowflakeIdGenerator idGen;
    @Mock PrepaidFeeAssessmentService prepaidFeeAssessmentService;
    @Mock PrepaidFeePostingService prepaidFeePostingService;
    TransactionOperations transactionOperations = new ImmediateTransactionOperations();

    VirtualCardService service;

    @BeforeEach
    void setUp() {
        service = new VirtualCardService(virtualCardRepo, cardProgramRepo, cardControlProfileRepo,
                cardCreateRequestRepo, cardIssuerReconciliationRepo,
                cardholderRepo, issuerPartnerRepo,
                accountRepo, ledger, issuerCards, idGen,
                new VccFundingPostingRule(idGen),
                new VccCloseSweepPostingRule(idGen),
                new VccWithdrawPostingRule(idGen),
                prepaidFeeAssessmentService,
                prepaidFeePostingService,
                transactionOperations);
        lenient().when(prepaidFeeAssessmentService.assessAndPersist(any()))
                .thenReturn(Optional.empty());
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
        verify(ledger, never()).postAllIfNew(any(), any(), any());
    }

    @Test
    void closeCard_uses_stable_idempotency_event_for_sweep() {
        when(virtualCardRepo.findById(CARD_ID)).thenReturn(Optional.of(card()));
        when(accountRepo.findByIdForUpdate(VCC_ACCOUNT_ID)).thenReturn(
                Optional.of(vccAccount(new BigDecimal("12.00"))));
        when(accountRepo.findByIdForUpdate(HOLD_ACCOUNT_ID)).thenReturn(
                Optional.of(holdAccount(BigDecimal.ZERO)));
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));
        when(idGen.generate(com.masonx.common.id.MasonXIdPrefix.CARD_CLOSE_TRANSACTION.prefix()))
                .thenReturn("tx_close_1");
        when(ledger.postAllIfNew(any(), eq("vcc_close_" + CARD_ID), eq("vcc-card-close")))
                .thenReturn(true);

        service.closeCard(CARD_ID, MERCHANT_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerPostingCommand>> txCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledger).postAllIfNew(txCaptor.capture(), eq("vcc_close_" + CARD_ID), eq("vcc-card-close"));
        verify(ledger, never()).postDirect(any());
        assertThat(txCaptor.getValue().get(0).transactionId()).isEqualTo("tx_close_1");
        assertThat(txCaptor.getValue().get(0).entries())
                .allSatisfy(entry -> assertThat(entry.sourceEventId()).isEqualTo("vcc_close_" + CARD_ID));
        verify(virtualCardRepo).updateStatus(CARD_ID, VirtualCardStatus.CLOSED);
        verify(accountRepo).updateStatus(VCC_ACCOUNT_ID, LedgerAccountStatus.CLOSED);
        verify(accountRepo).updateStatus(HOLD_ACCOUNT_ID, LedgerAccountStatus.CLOSED);
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
    void withdrawCard_uses_stable_idempotency_event_for_retries() {
        when(virtualCardRepo.findById(CARD_ID)).thenReturn(Optional.of(card()));
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));
        when(accountRepo.findByIdForUpdate(VCC_ACCOUNT_ID)).thenReturn(
                Optional.of(vccAccount(new BigDecimal("25.00"))));
        when(accountRepo.findById(VCC_ACCOUNT_ID)).thenReturn(
                Optional.of(vccAccount(new BigDecimal("20.00"))));
        when(accountRepo.findById(HOLD_ACCOUNT_ID)).thenReturn(Optional.of(holdAccount(BigDecimal.ZERO)));
        when(idGen.generate(com.masonx.common.id.MasonXIdPrefix.CARD_WITHDRAW_TRANSACTION.prefix()))
                .thenReturn("tx_wd_1", "tx_wd_2");
        when(ledger.postAllIfNew(any(), any(), eq("vcc-card-withdraw"))).thenReturn(true, false);

        WithdrawVccRequest req = new WithdrawVccRequest(MERCHANT_ID, "client-withdraw-1", new BigDecimal("5.00"));

        service.withdrawCard(CARD_ID, req);
        service.withdrawCard(CARD_ID, req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerPostingCommand>> txCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(ledger, times(2)).postAllIfNew(txCaptor.capture(), eventCaptor.capture(), eq("vcc-card-withdraw"));
        verify(ledger, never()).postDirect(any());

        assertThat(eventCaptor.getAllValues()).hasSize(2);
        assertThat(eventCaptor.getAllValues().get(0)).isEqualTo(eventCaptor.getAllValues().get(1));
        assertThat(eventCaptor.getAllValues().get(0)).startsWith("vcc_withdraw_");
        assertThat(txCaptor.getAllValues())
                .allSatisfy(commands -> assertThat(commands.get(0).entries())
                        .allSatisfy(entry -> assertThat(entry.sourceEventId())
                                .isEqualTo(eventCaptor.getAllValues().get(0))));
    }

    @Test
    void withdrawCard_rejects_insufficient_available_balance() {
        when(virtualCardRepo.findById(CARD_ID)).thenReturn(Optional.of(card()));
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));
        when(accountRepo.findByIdForUpdate(VCC_ACCOUNT_ID)).thenReturn(
                Optional.of(vccAccount(new BigDecimal("4.99"))));

        assertThatThrownBy(() -> service.withdrawCard(CARD_ID,
                new WithdrawVccRequest(MERCHANT_ID, "client-withdraw-1", new BigDecimal("5.00"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient available card balance");

        verify(ledger, never()).postAllIfNew(any(), any(), any());
    }

    @Test
    void getCard_rejects_same_merchant_wrong_mode() {
        when(virtualCardRepo.findById(CARD_ID)).thenReturn(Optional.of(card()));
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount(Mode.TEST)));

        assertThatThrownBy(() -> service.getCard(CARD_ID, MERCHANT_ID, Mode.LIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("merchant/mode");

        verify(accountRepo, never()).findById(VCC_ACCOUNT_ID);
    }

    @Test
    void listCards_scopes_by_merchant_and_mode() {
        when(virtualCardRepo.countByMerchantIdAndMode(MERCHANT_ID, Mode.LIVE)).thenReturn(0L);
        when(virtualCardRepo.findByMerchantIdAndMode(MERCHANT_ID, Mode.LIVE, 0, 20))
                .thenReturn(List.of());

        var result = service.listCards(MERCHANT_ID, Mode.LIVE, 0, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        verify(virtualCardRepo).countByMerchantIdAndMode(MERCHANT_ID, Mode.LIVE);
        verify(virtualCardRepo).findByMerchantIdAndMode(MERCHANT_ID, Mode.LIVE, 0, 20);
    }

    @Test
    void lockCard_calls_issuer_adapter_and_marks_locked() {
        when(virtualCardRepo.findById(CARD_ID)).thenReturn(Optional.of(card()), Optional.of(card(VirtualCardStatus.LOCKED)));
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));
        when(issuerPartnerRepo.findByIdForMerchant(ISSUER_PARTNER_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(issuerPartner(IssuerPartnerStatus.ACTIVE)));
        when(issuerCards.require(IssuerPartnerType.RAIL_SIM)).thenReturn(issuerCardProvider);
        when(issuerCardProvider.lockCard("railsim_ctok_abc123", "merchant-request", "issuer_card_lock:" + CARD_ID))
                .thenReturn(new com.masonx.virtualaccount.issuer.IssuerCardResult(
                        "railsim_ctok_abc123", IssuerCardStatus.LOCKED));
        when(accountRepo.findById(VCC_ACCOUNT_ID)).thenReturn(Optional.of(vccAccount(BigDecimal.ZERO)));
        when(accountRepo.findById(HOLD_ACCOUNT_ID)).thenReturn(Optional.of(holdAccount(BigDecimal.ZERO)));

        var response = service.lockCard(CARD_ID, MERCHANT_ID, "merchant-request");

        verify(issuerCardProvider)
                .lockCard("railsim_ctok_abc123", "merchant-request", "issuer_card_lock:" + CARD_ID);
        verify(virtualCardRepo).updateStatus(CARD_ID, VirtualCardStatus.LOCKED);
        assertThat(response.status()).isEqualTo("LOCKED");
    }

    @Test
    void lockCard_records_reconciliation_when_local_update_fails_after_issuer_success() {
        when(virtualCardRepo.findById(CARD_ID)).thenReturn(Optional.of(card()));
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));
        when(issuerPartnerRepo.findByIdForMerchant(ISSUER_PARTNER_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(issuerPartner(IssuerPartnerStatus.ACTIVE)));
        when(issuerCards.require(IssuerPartnerType.RAIL_SIM)).thenReturn(issuerCardProvider);
        when(issuerCardProvider.lockCard("railsim_ctok_abc123", "merchant-request", "issuer_card_lock:" + CARD_ID))
                .thenReturn(new com.masonx.virtualaccount.issuer.IssuerCardResult(
                        "railsim_ctok_abc123", IssuerCardStatus.LOCKED));
        doThrow(new IllegalStateException("database unavailable"))
                .when(virtualCardRepo).updateStatus(CARD_ID, VirtualCardStatus.LOCKED);

        assertThatThrownBy(() -> service.lockCard(CARD_ID, MERCHANT_ID, "merchant-request"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");

        verify(cardIssuerReconciliationRepo).upsertOpen(any());
    }

    @Test
    void unlockCard_calls_issuer_adapter_and_marks_active() {
        when(virtualCardRepo.findById(CARD_ID)).thenReturn(
                Optional.of(card(VirtualCardStatus.LOCKED)), Optional.of(card(VirtualCardStatus.ACTIVE)));
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));
        when(issuerPartnerRepo.findByIdForMerchant(ISSUER_PARTNER_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(issuerPartner(IssuerPartnerStatus.ACTIVE)));
        when(issuerCards.require(IssuerPartnerType.RAIL_SIM)).thenReturn(issuerCardProvider);
        when(issuerCardProvider.unlockCard("railsim_ctok_abc123", "issuer_card_unlock:" + CARD_ID))
                .thenReturn(new com.masonx.virtualaccount.issuer.IssuerCardResult(
                        "railsim_ctok_abc123", IssuerCardStatus.ACTIVE));
        when(accountRepo.findById(VCC_ACCOUNT_ID)).thenReturn(Optional.of(vccAccount(BigDecimal.ZERO)));
        when(accountRepo.findById(HOLD_ACCOUNT_ID)).thenReturn(Optional.of(holdAccount(BigDecimal.ZERO)));

        var response = service.unlockCard(CARD_ID, MERCHANT_ID);

        verify(issuerCardProvider).unlockCard("railsim_ctok_abc123", "issuer_card_unlock:" + CARD_ID);
        verify(virtualCardRepo).updateStatus(CARD_ID, VirtualCardStatus.ACTIVE);
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    void terminateCard_rejects_remaining_balance_or_hold() {
        when(virtualCardRepo.findById(CARD_ID)).thenReturn(Optional.of(card()));
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));
        when(accountRepo.findById(VCC_ACCOUNT_ID)).thenReturn(Optional.of(vccAccount(new BigDecimal("1.00"))));
        when(accountRepo.findById(HOLD_ACCOUNT_ID)).thenReturn(Optional.of(holdAccount(BigDecimal.ZERO)));

        assertThatThrownBy(() -> service.terminateCard(CARD_ID, MERCHANT_ID, "merchant-request"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("remaining balance or open hold");

        verify(issuerCardProvider, never()).terminateCard(any(), any(), any());
        verify(virtualCardRepo, never()).updateStatus(CARD_ID, VirtualCardStatus.TERMINATED);
    }

    @Test
    void terminateCard_calls_issuer_adapter_and_closes_accounts() {
        when(virtualCardRepo.findById(CARD_ID)).thenReturn(
                Optional.of(card()), Optional.of(card(VirtualCardStatus.TERMINATED)));
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));
        when(accountRepo.findById(VCC_ACCOUNT_ID)).thenReturn(Optional.of(vccAccount(BigDecimal.ZERO)));
        when(accountRepo.findById(HOLD_ACCOUNT_ID)).thenReturn(Optional.of(holdAccount(BigDecimal.ZERO)));
        when(issuerPartnerRepo.findByIdForMerchant(ISSUER_PARTNER_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(issuerPartner(IssuerPartnerStatus.ACTIVE)));
        when(issuerCards.require(IssuerPartnerType.RAIL_SIM)).thenReturn(issuerCardProvider);
        when(issuerCardProvider.terminateCard("railsim_ctok_abc123", "merchant-request",
                "issuer_card_terminate:" + CARD_ID))
                .thenReturn(new com.masonx.virtualaccount.issuer.IssuerCardResult(
                        "railsim_ctok_abc123", IssuerCardStatus.TERMINATED));

        var response = service.terminateCard(CARD_ID, MERCHANT_ID, "merchant-request");

        verify(issuerCardProvider)
                .terminateCard("railsim_ctok_abc123", "merchant-request", "issuer_card_terminate:" + CARD_ID);
        verify(virtualCardRepo).updateStatus(CARD_ID, VirtualCardStatus.TERMINATED);
        verify(accountRepo).updateStatus(VCC_ACCOUNT_ID, LedgerAccountStatus.CLOSED);
        verify(accountRepo).updateStatus(HOLD_ACCOUNT_ID, LedgerAccountStatus.CLOSED);
        assertThat(response.status()).isEqualTo("TERMINATED");
    }

    @Test
    void createCard_creates_primary_and_hold_accounts() {
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));
        when(cardProgramRepo.findByIdForMerchant(PROGRAM_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(cardProgram(CardProgramStatus.ACTIVE, "USD")));
        when(cardholderRepo.findByIdForMerchant(CARDHOLDER_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(cardholder(CardholderKycStatus.ACTIVE)));
        when(issuerPartnerRepo.findByIdForMerchant(ISSUER_PARTNER_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(issuerPartner(IssuerPartnerStatus.ACTIVE)));
        when(idGen.generate(com.masonx.common.id.MasonXIdPrefix.VIRTUAL_CARD.prefix()))
                .thenReturn(CARD_ID);
        when(idGen.generate(com.masonx.common.id.MasonXIdPrefix.VCC_ACCOUNT.prefix()))
                .thenReturn(VCC_ACCOUNT_ID, HOLD_ACCOUNT_ID);
        when(cardCreateRequestRepo.find(MERCHANT_ID, Mode.TEST, CREATE_KEY))
                .thenReturn(Optional.of(createRequest("PROCESSING")));
        when(issuerCards.require(IssuerPartnerType.RAIL_SIM)).thenReturn(issuerCardProvider);
        when(issuerCardProvider.createCard(any())).thenReturn(issuerCard());

        var response = service.createCard(new CreateVccRequest(
                MERCHANT_ID, null, CREATE_KEY, OWNER_ACCOUNT_ID, PROGRAM_ID, CARDHOLDER_ID,
                "USD", new BigDecimal("100.00"), LocalDate.of(2027, 1, 1)));

        ArgumentCaptor<LedgerAccount> accountCaptor = ArgumentCaptor.forClass(LedgerAccount.class);
        verify(accountRepo, times(2)).saveIfAbsent(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues())
                .extracting(LedgerAccount::ledgerAccountType)
                .containsExactly(LedgerAccountType.PREPAID_CARD, LedgerAccountType.PREPAID_CARD_HOLD);

        ArgumentCaptor<VirtualCard> cardCaptor = ArgumentCaptor.forClass(VirtualCard.class);
        verify(virtualCardRepo).saveIfAbsent(cardCaptor.capture());
        assertThat(cardCaptor.getValue().vccAccountId()).isEqualTo(VCC_ACCOUNT_ID);
        assertThat(cardCaptor.getValue().holdAccountId()).isEqualTo(HOLD_ACCOUNT_ID);
        assertThat(cardCaptor.getValue().programId()).isEqualTo(PROGRAM_ID);
        assertThat(cardCaptor.getValue().issuerPartnerId()).isEqualTo(ISSUER_PARTNER_ID);
        assertThat(cardCaptor.getValue().cardholderId()).isEqualTo(CARDHOLDER_ID);
        assertThat(cardCaptor.getValue().cardTokenId()).isEqualTo("ctok_abc123");
        assertThat(cardCaptor.getValue().externalIssuerCardId()).isEqualTo("railsim_ctok_abc123");
        assertThat(cardCaptor.getValue().externalCardToken()).isEqualTo("ctok_abc123");
        assertThat(response.testPan()).isEqualTo("9999991234567890");

        ArgumentCaptor<CreateIssuerCardCommand> commandCaptor = ArgumentCaptor.forClass(CreateIssuerCardCommand.class);
        verify(issuerCardProvider).createCard(commandCaptor.capture());
        assertThat(commandCaptor.getValue().idempotencyKey()).startsWith("issuer_card_create:");
        assertThat(commandCaptor.getValue().idempotencyKey()).doesNotContain(CARD_ID);
        assertThat(commandCaptor.getValue().issuerPartnerType()).isEqualTo(IssuerPartnerType.RAIL_SIM);
        verify(cardCreateRequestRepo).markSucceeded(MERCHANT_ID, Mode.TEST, CREATE_KEY);
    }

    @Test
    void createCard_postsCardCreateFeeWhenAssessmentMatches() {
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));
        when(cardProgramRepo.findByIdForMerchant(PROGRAM_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(cardProgram(CardProgramStatus.ACTIVE, "USD")));
        when(cardholderRepo.findByIdForMerchant(CARDHOLDER_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(cardholder(CardholderKycStatus.ACTIVE)));
        when(issuerPartnerRepo.findByIdForMerchant(ISSUER_PARTNER_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(issuerPartner(IssuerPartnerStatus.ACTIVE)));
        when(idGen.generate(com.masonx.common.id.MasonXIdPrefix.VIRTUAL_CARD.prefix()))
                .thenReturn(CARD_ID);
        when(idGen.generate(com.masonx.common.id.MasonXIdPrefix.VCC_ACCOUNT.prefix()))
                .thenReturn(VCC_ACCOUNT_ID, HOLD_ACCOUNT_ID);
        when(cardCreateRequestRepo.find(MERCHANT_ID, Mode.TEST, CREATE_KEY))
                .thenReturn(Optional.of(createRequest("PROCESSING")));
        when(issuerCards.require(IssuerPartnerType.RAIL_SIM)).thenReturn(issuerCardProvider);
        when(issuerCardProvider.createCard(any())).thenReturn(issuerCard());
        PrepaidFeeAssessmentSnapshot snapshot = feeSnapshot();
        when(prepaidFeeAssessmentService.assessAndPersist(any())).thenReturn(Optional.of(snapshot));

        service.createCard(new CreateVccRequest(
                MERCHANT_ID, null, CREATE_KEY, OWNER_ACCOUNT_ID, PROGRAM_ID, CARDHOLDER_ID,
                "USD", new BigDecimal("100.00"), LocalDate.of(2027, 1, 1)));

        ArgumentCaptor<AssessPrepaidFeeCommand> feeCommandCaptor =
                ArgumentCaptor.forClass(AssessPrepaidFeeCommand.class);
        verify(prepaidFeeAssessmentService).assessAndPersist(feeCommandCaptor.capture());
        assertThat(feeCommandCaptor.getValue().eventType()).isEqualTo("CARD_CREATE");
        assertThat(feeCommandCaptor.getValue().eventId()).isEqualTo(CARD_ID);
        assertThat(feeCommandCaptor.getValue().programId()).isEqualTo(PROGRAM_ID);
        assertThat(feeCommandCaptor.getValue().bin()).isEqualTo("999999");
        assertThat(feeCommandCaptor.getValue().context())
                .containsEntry("fundingWalletId", OWNER_ACCOUNT_ID)
                .containsEntry("cardholderId", CARDHOLDER_ID);
        verify(prepaidFeePostingService).postAssessmentFeesFromWallet(snapshot, OWNER_ACCOUNT_ID);
    }

    @Test
    void createCard_rejects_inactive_program() {
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));
        when(cardProgramRepo.findByIdForMerchant(PROGRAM_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(cardProgram(CardProgramStatus.DRAFT, "USD")));

        assertThatThrownBy(() -> service.createCard(new CreateVccRequest(
                MERCHANT_ID, null, CREATE_KEY, OWNER_ACCOUNT_ID, PROGRAM_ID, CARDHOLDER_ID,
                "USD", new BigDecimal("100.00"), LocalDate.of(2027, 1, 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("program in status");

        verify(accountRepo, times(0)).save(any());
        verify(virtualCardRepo, never()).save(any());
    }

    @Test
    void createCard_rejects_inactive_cardholder() {
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));
        when(cardProgramRepo.findByIdForMerchant(PROGRAM_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(cardProgram(CardProgramStatus.ACTIVE, "USD")));
        when(issuerPartnerRepo.findByIdForMerchant(ISSUER_PARTNER_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(issuerPartner(IssuerPartnerStatus.ACTIVE)));
        when(cardholderRepo.findByIdForMerchant(CARDHOLDER_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(cardholder(CardholderKycStatus.PENDING)));

        assertThatThrownBy(() -> service.createCard(new CreateVccRequest(
                MERCHANT_ID, null, CREATE_KEY, OWNER_ACCOUNT_ID, PROGRAM_ID, CARDHOLDER_ID,
                "USD", new BigDecimal("100.00"), LocalDate.of(2027, 1, 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KYC status");

        verify(accountRepo, times(0)).save(any());
        verify(virtualCardRepo, never()).save(any());
    }

    @Test
    void createCard_fails_before_ledger_account_creation_when_issuer_adapter_missing() {
        when(accountRepo.findById(OWNER_ACCOUNT_ID)).thenReturn(Optional.of(ownerAccount()));
        when(cardProgramRepo.findByIdForMerchant(PROGRAM_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(cardProgram(CardProgramStatus.ACTIVE, "USD")));
        when(issuerPartnerRepo.findByIdForMerchant(ISSUER_PARTNER_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(issuerPartner(IssuerPartnerStatus.ACTIVE)));
        when(cardholderRepo.findByIdForMerchant(CARDHOLDER_ID, MERCHANT_ID, Mode.TEST))
                .thenReturn(Optional.of(cardholder(CardholderKycStatus.ACTIVE)));
        when(idGen.generate(com.masonx.common.id.MasonXIdPrefix.VIRTUAL_CARD.prefix()))
                .thenReturn(CARD_ID);
        when(idGen.generate(com.masonx.common.id.MasonXIdPrefix.VCC_ACCOUNT.prefix()))
                .thenReturn(VCC_ACCOUNT_ID, HOLD_ACCOUNT_ID);
        when(cardCreateRequestRepo.find(MERCHANT_ID, Mode.TEST, CREATE_KEY))
                .thenReturn(Optional.of(createRequest("PROCESSING")));
        when(issuerCards.require(IssuerPartnerType.RAIL_SIM))
                .thenThrow(new IllegalStateException("No issuer card provider configured for: RAIL_SIM"));

        assertThatThrownBy(() -> service.createCard(new CreateVccRequest(
                MERCHANT_ID, null, CREATE_KEY, OWNER_ACCOUNT_ID, PROGRAM_ID, CARDHOLDER_ID,
                "USD", new BigDecimal("100.00"), LocalDate.of(2027, 1, 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No issuer card provider");

        verify(accountRepo, never()).save(any());
        verify(virtualCardRepo, never()).save(any());
        verify(cardCreateRequestRepo).markFailed(eq(MERCHANT_ID), eq(Mode.TEST), eq(CREATE_KEY), any());
    }

    private static VirtualCard card() {
        return card(VirtualCardStatus.ACTIVE);
    }

    private static VirtualCard card(VirtualCardStatus status) {
        return new VirtualCard(
                CARD_ID,
                "ctok_abc123",
                "999999****1234",
                "999999",
                VCC_ACCOUNT_ID,
                HOLD_ACCOUNT_ID,
                OWNER_ACCOUNT_ID,
                PROGRAM_ID,
                ISSUER_PARTNER_ID,
                CARDHOLDER_ID,
                "railsim_ctok_abc123",
                "ctok_abc123",
                status,
                null,
                "USD",
                LocalDate.of(2027, 1, 1),
                Instant.now(),
                Instant.now());
    }

    private static CardProgram cardProgram(CardProgramStatus status, String currency) {
        return new CardProgram(
                PROGRAM_ID,
                MERCHANT_ID,
                Mode.TEST,
                ISSUER_PARTNER_ID,
                "Expense Cards",
                currency,
                "{}",
                status,
                CardProgramSystemOfRecord.INTERNAL,
                CardProgramFundingModel.SIMULATED,
                "{}",
                "{}",
                "{}",
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    private static Cardholder cardholder(CardholderKycStatus status) {
        return new Cardholder(
                CARDHOLDER_ID,
                MERCHANT_ID,
                Mode.TEST,
                CardholderType.EMPLOYEE,
                null,
                status,
                "Jane",
                "employee-42",
                null,
                Instant.now(),
                Instant.now());
    }

    private static IssuerPartner issuerPartner(IssuerPartnerStatus status) {
        return new IssuerPartner(
                ISSUER_PARTNER_ID,
                MERCHANT_ID,
                Mode.TEST,
                "Rail Sim",
                IssuerPartnerType.RAIL_SIM,
                status,
                null,
                "{}",
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    private static CreateIssuerCardResult issuerCard() {
        return new CreateIssuerCardResult(
                "railsim_ctok_abc123",
                "ctok_abc123",
                "ctok_abc123",
                "999999****7890",
                "999999",
                "9999991234567890",
                LocalDate.of(2027, 1, 1),
                IssuerCardStatus.ACTIVE);
    }

    private static CardCreateRequest createRequest(String status) {
        return new CardCreateRequest(
                MERCHANT_ID,
                Mode.TEST,
                CREATE_KEY,
                CARD_ID,
                VCC_ACCOUNT_ID,
                HOLD_ACCOUNT_ID,
                status,
                null);
    }

    private static PrepaidFeeAssessmentSnapshot feeSnapshot() {
        Instant now = Instant.now();
        PrepaidFeeAssessment assessment = new PrepaidFeeAssessment(
                "feeas_1",
                MERCHANT_ID,
                Mode.TEST,
                "CARD_CREATE",
                CARD_ID,
                PROGRAM_ID,
                CARD_ID,
                "fees_1",
                1,
                "{}",
                "[]",
                "{\"USD\":1.00}",
                "{}",
                now);
        PrepaidFeeAssessmentLine line = new PrepaidFeeAssessmentLine(
                1L,
                "feeas_1",
                MERCHANT_ID,
                Mode.TEST,
                "create-card",
                1,
                "Create card",
                "fixed",
                "card_create_fixed",
                "MERCHANT_VISIBLE",
                "USD",
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                "HALF_UP",
                2,
                "{}",
                now);
        return new PrepaidFeeAssessmentSnapshot(assessment, List.of(line));
    }

    private static LedgerAccount vccAccount(BigDecimal balance) {
        return new LedgerAccount(
                VCC_ACCOUNT_ID,
                Mode.TEST,
                LedgerAccountRole.TENANT,
                "org_1",
                MERCHANT_ID,
                null,
                LedgerAccountType.PREPAID_CARD,
                "USD",
                AssetClass.FIAT,
                2,
                NormalBalance.CREDIT,
                balance,
                LedgerAccountStatus.ACTIVE);
    }

    private static LedgerAccount holdAccount(BigDecimal balance) {
        return new LedgerAccount(
                HOLD_ACCOUNT_ID,
                Mode.TEST,
                LedgerAccountRole.TENANT,
                "org_1",
                MERCHANT_ID,
                null,
                LedgerAccountType.PREPAID_CARD_HOLD,
                "USD",
                AssetClass.FIAT,
                2,
                NormalBalance.CREDIT,
                balance,
                LedgerAccountStatus.ACTIVE);
    }

    private static LedgerAccount ownerAccount() {
        return ownerAccount(Mode.TEST);
    }

    private static LedgerAccount ownerAccount(Mode mode) {
        return new LedgerAccount(
                OWNER_ACCOUNT_ID,
                mode,
                LedgerAccountRole.TENANT,
                "org_1",
                MERCHANT_ID,
                null,
                LedgerAccountType.WALLET,
                "USD",
                AssetClass.FIAT,
                2,
                NormalBalance.CREDIT,
                new BigDecimal("100.00"),
                LedgerAccountStatus.ACTIVE);
    }

    private static final class ImmediateTransactionOperations implements TransactionOperations {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    }
}
