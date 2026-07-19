package com.masonx.virtualaccount.vcc;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.CardAuthorizationRepository;
import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.AssetClass;
import com.masonx.virtualaccount.domain.constant.CardAuthorizationStatus;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.LedgerAccountRole;
import com.masonx.virtualaccount.domain.constant.LedgerAccountStatus;
import com.masonx.virtualaccount.domain.constant.LedgerAccountType;
import com.masonx.virtualaccount.domain.constant.NormalBalance;
import com.masonx.virtualaccount.domain.constant.VirtualCardStatus;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.ledger.posting.CardAuthHoldPostingRule;
import com.masonx.virtualaccount.domain.po.CardAuthorization;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import com.masonx.virtualaccount.vcc.dto.IssuerAuthRequest;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardAuthorizationServiceTest {

    private static final String ISSUER_ID = "RAIL_SIM";
    private static final String AUTHORIZATION_ID = "auth_abc123";
    private static final String CARD_ID = "card_1";
    private static final String CARD_TOKEN_ID = "ctok_abc123";
    private static final String MASKED_PAN = "999999****1234";
    private static final String CARD_ACCOUNT_ID = "ac_card";
    private static final String HOLD_ACCOUNT_ID = "ac_hold";

    @Mock VirtualCardRepository virtualCardRepo;
    @Mock LedgerAccountRepository accountRepo;
    @Mock CardAuthorizationRepository authorizationRepo;
    @Mock LedgerFacade ledger;
    @Mock SnowflakeIdGenerator idGen;

    CardAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new CardAuthorizationService(virtualCardRepo, accountRepo, authorizationRepo,
                ledger, new CardAuthHoldPostingRule(idGen), idGen);
    }

    @Test
    void authorize_posts_hold_and_records_decision_when_approved() {
        when(authorizationRepo.findByIssuerIdAndAuthorizationId(ISSUER_ID, AUTHORIZATION_ID))
                .thenReturn(Optional.empty());
        when(virtualCardRepo.findActiveByCardTokenId(CARD_TOKEN_ID)).thenReturn(Optional.of(card()));
        when(accountRepo.findByIdForUpdate(CARD_ACCOUNT_ID))
                .thenReturn(Optional.of(cardAccount(new BigDecimal("100.00"))));
        when(accountRepo.findByIdForUpdate(HOLD_ACCOUNT_ID)).thenReturn(Optional.of(holdAccount()));
        when(idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix())).thenReturn("tx_rail_1");
        when(idGen.generate(MasonXIdPrefix.CARD_AUTHORIZATION.prefix())).thenReturn("cauth_1");
        when(ledger.postAllIfNew(any(), any(), eq("card-auth"))).thenReturn(true);
        when(authorizationRepo.insert(any())).thenReturn(true);

        var response = service.authorize(ISSUER_ID, request());

        assertThat(response.decision()).isEqualTo("APPROVED");
        assertThat(response.reason()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerPostingCommand>> txCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(ledger).postAllIfNew(txCaptor.capture(), eventCaptor.capture(), eq("card-auth"));

        String eventId = eventCaptor.getValue();
        assertThat(eventId).startsWith("card_auth_");
        LedgerPostingCommand tx = txCaptor.getValue().get(0);
        assertThat(tx.entries()).hasSize(2);
        assertThat(tx.entries())
                .anySatisfy(entry -> {
                    assertThat(entry.ledgerAccountId()).isEqualTo(HOLD_ACCOUNT_ID);
                    assertThat(entry.direction()).isEqualTo(Direction.DEBIT);
                    assertThat(entry.sourceEventId()).isEqualTo(eventId);
                })
                .anySatisfy(entry -> {
                    assertThat(entry.ledgerAccountId()).isEqualTo(CARD_ACCOUNT_ID);
                    assertThat(entry.direction()).isEqualTo(Direction.CREDIT);
                    assertThat(entry.sourceEventId()).isEqualTo(eventId);
                });

        CardAuthorization saved = capturedDecision();
        assertThat(saved.issuerId()).isEqualTo(ISSUER_ID);
        assertThat(saved.authorizationId()).isEqualTo(AUTHORIZATION_ID);
        assertThat(saved.cardId()).isEqualTo(CARD_ID);
        assertThat(saved.decision()).isEqualTo("APPROVED");
        assertThat(saved.declineReason()).isNull();
        assertThat(saved.holdEventId()).isEqualTo(eventId);
        assertThat(saved.status()).isEqualTo(CardAuthorizationStatus.AUTHORIZED);
    }

    @Test
    void authorize_replays_stored_approval_without_touching_ledger_or_accounts() {
        when(authorizationRepo.findByIssuerIdAndAuthorizationId(ISSUER_ID, AUTHORIZATION_ID))
                .thenReturn(Optional.of(decision("APPROVED", null, CardAuthorizationStatus.AUTHORIZED)));

        var response = service.authorize(ISSUER_ID, request());

        assertThat(response.decision()).isEqualTo("APPROVED");
        assertThat(response.reason()).isNull();
        verify(ledger, never()).postAllIfNew(any(), any(), any());
        verify(authorizationRepo, never()).insert(any());
        verify(accountRepo, never()).findByIdForUpdate(any());
    }

    @Test
    void authorize_replays_stored_decline() {
        when(authorizationRepo.findByIssuerIdAndAuthorizationId(ISSUER_ID, AUTHORIZATION_ID))
                .thenReturn(Optional.of(
                        decision("DECLINED", "INSUFFICIENT_FUNDS", CardAuthorizationStatus.DECLINED)));

        var response = service.authorize(ISSUER_ID, request());

        assertThat(response.decision()).isEqualTo("DECLINED");
        assertThat(response.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        verify(ledger, never()).postAllIfNew(any(), any(), any());
        verify(authorizationRepo, never()).insert(any());
    }

    @Test
    void authorize_replays_duplicate_committed_while_waiting_for_account_lock() {
        when(authorizationRepo.findByIssuerIdAndAuthorizationId(ISSUER_ID, AUTHORIZATION_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(decision("APPROVED", null, CardAuthorizationStatus.AUTHORIZED)));
        when(virtualCardRepo.findActiveByCardTokenId(CARD_TOKEN_ID)).thenReturn(Optional.of(card()));
        when(accountRepo.findByIdForUpdate(CARD_ACCOUNT_ID))
                .thenReturn(Optional.of(cardAccount(new BigDecimal("100.00"))));
        when(accountRepo.findByIdForUpdate(HOLD_ACCOUNT_ID)).thenReturn(Optional.of(holdAccount()));

        var response = service.authorize(ISSUER_ID, request());

        assertThat(response.decision()).isEqualTo("APPROVED");
        verify(ledger, never()).postAllIfNew(any(), any(), any());
        verify(authorizationRepo, never()).insert(any());
    }

    @Test
    void authorize_declines_and_records_when_balance_insufficient() {
        when(authorizationRepo.findByIssuerIdAndAuthorizationId(ISSUER_ID, AUTHORIZATION_ID))
                .thenReturn(Optional.empty());
        when(virtualCardRepo.findActiveByCardTokenId(CARD_TOKEN_ID)).thenReturn(Optional.of(card()));
        when(accountRepo.findByIdForUpdate(CARD_ACCOUNT_ID))
                .thenReturn(Optional.of(cardAccount(new BigDecimal("10.00"))));
        when(accountRepo.findByIdForUpdate(HOLD_ACCOUNT_ID)).thenReturn(Optional.of(holdAccount()));
        when(idGen.generate(MasonXIdPrefix.CARD_AUTHORIZATION.prefix())).thenReturn("cauth_1");
        when(authorizationRepo.insert(any())).thenReturn(true);

        var response = service.authorize(ISSUER_ID, request());

        assertThat(response.decision()).isEqualTo("DECLINED");
        assertThat(response.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        verify(ledger, never()).postAllIfNew(any(), any(), any());

        CardAuthorization saved = capturedDecision();
        assertThat(saved.decision()).isEqualTo("DECLINED");
        assertThat(saved.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(saved.holdEventId()).isNull();
        assertThat(saved.status()).isEqualTo(CardAuthorizationStatus.DECLINED);
    }

    @Test
    void authorize_fails_closed_when_hold_event_consumed_without_decision_record() {
        when(authorizationRepo.findByIssuerIdAndAuthorizationId(ISSUER_ID, AUTHORIZATION_ID))
                .thenReturn(Optional.empty());
        when(virtualCardRepo.findActiveByCardTokenId(CARD_TOKEN_ID)).thenReturn(Optional.of(card()));
        when(accountRepo.findByIdForUpdate(CARD_ACCOUNT_ID))
                .thenReturn(Optional.of(cardAccount(new BigDecimal("100.00"))));
        when(accountRepo.findByIdForUpdate(HOLD_ACCOUNT_ID)).thenReturn(Optional.of(holdAccount()));
        when(idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix())).thenReturn("tx_rail_1");
        when(idGen.generate(MasonXIdPrefix.CARD_AUTHORIZATION.prefix())).thenReturn("cauth_1");
        when(ledger.postAllIfNew(any(), any(), eq("card-auth"))).thenReturn(false);
        when(authorizationRepo.insert(any())).thenReturn(true);

        var response = service.authorize(ISSUER_ID, request());

        assertThat(response.decision()).isEqualTo("DECLINED");
        assertThat(response.reason()).isEqualTo("AUTH_STATE_ANOMALY");

        CardAuthorization saved = capturedDecision();
        assertThat(saved.decision()).isEqualTo("DECLINED");
        assertThat(saved.declineReason()).isEqualTo("AUTH_STATE_ANOMALY");
        assertThat(saved.status()).isEqualTo(CardAuthorizationStatus.DECLINED);
    }

    @Test
    void authorize_replays_stored_decision_when_decision_insert_loses_identity_race() {
        when(authorizationRepo.findByIssuerIdAndAuthorizationId(ISSUER_ID, AUTHORIZATION_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(decision("APPROVED", null, CardAuthorizationStatus.AUTHORIZED)));
        when(virtualCardRepo.findActiveByCardTokenId(CARD_TOKEN_ID)).thenReturn(Optional.of(card()));
        when(accountRepo.findByIdForUpdate(CARD_ACCOUNT_ID))
                .thenReturn(Optional.of(cardAccount(new BigDecimal("100.00"))));
        when(accountRepo.findByIdForUpdate(HOLD_ACCOUNT_ID)).thenReturn(Optional.of(holdAccount()));
        when(idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix())).thenReturn("tx_rail_1");
        when(idGen.generate(MasonXIdPrefix.CARD_AUTHORIZATION.prefix())).thenReturn("cauth_1");
        when(ledger.postAllIfNew(any(), any(), eq("card-auth"))).thenReturn(false);
        when(authorizationRepo.insert(any())).thenReturn(false);

        var response = service.authorize(ISSUER_ID, request());

        assertThat(response.decision()).isEqualTo("APPROVED");
        assertThat(response.reason()).isNull();
    }

    @Test
    void authorize_declines_unknown_card_without_recording() {
        when(authorizationRepo.findByIssuerIdAndAuthorizationId(ISSUER_ID, AUTHORIZATION_ID))
                .thenReturn(Optional.empty());
        when(virtualCardRepo.findActiveByCardTokenId(CARD_TOKEN_ID)).thenReturn(Optional.empty());

        var response = service.authorize(ISSUER_ID, request());

        assertThat(response.decision()).isEqualTo("DECLINED");
        assertThat(response.reason()).isEqualTo("CARD_NOT_FOUND");
        verify(ledger, never()).postAllIfNew(any(), any(), any());
        verify(authorizationRepo, never()).insert(any());
    }

    @Test
    void holdEventId_is_deterministic_and_scoped_by_issuer() {
        String first  = CardAuthorizationService.holdEventId(ISSUER_ID, AUTHORIZATION_ID);
        String second = CardAuthorizationService.holdEventId(ISSUER_ID, AUTHORIZATION_ID);
        String otherIssuer = CardAuthorizationService.holdEventId("OTHER_ISSUER", AUTHORIZATION_ID);

        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("card_auth_");
        assertThat(first.length()).isLessThanOrEqualTo(64);
        assertThat(otherIssuer).isNotEqualTo(first);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private CardAuthorization capturedDecision() {
        ArgumentCaptor<CardAuthorization> captor = ArgumentCaptor.forClass(CardAuthorization.class);
        verify(authorizationRepo).insert(captor.capture());
        return captor.getValue();
    }

    private static IssuerAuthRequest request() {
        return new IssuerAuthRequest(
                AUTHORIZATION_ID, CARD_TOKEN_ID, new BigDecimal("25.00"), "USD", "123456", "654321");
    }

    private static CardAuthorization decision(String decision, String reason,
                                              CardAuthorizationStatus status) {
        return new CardAuthorization(
                "cauth_1", ISSUER_ID, AUTHORIZATION_ID, CARD_ID, "123456", "654321",
                new BigDecimal("25.00"), "USD", decision, reason,
                "APPROVED".equals(decision) ? "card_auth_x" : null,
                status, Instant.now());
    }

    private static VirtualCard card() {
        return new VirtualCard(
                CARD_ID,
                CARD_TOKEN_ID,
                MASKED_PAN,
                "999999",
                CARD_ACCOUNT_ID,
                HOLD_ACCOUNT_ID,
                "ac_owner",
                VirtualCardStatus.ACTIVE,
                null,
                "USD",
                LocalDate.of(2027, 1, 1),
                Instant.now(),
                Instant.now());
    }

    private static LedgerAccount cardAccount(BigDecimal balance) {
        return new LedgerAccount(
                CARD_ACCOUNT_ID,
                Mode.TEST,
                LedgerAccountRole.TENANT,
                "org_1",
                "mer_1",
                null,
                LedgerAccountType.PREPAID_CARD,
                "USD",
                AssetClass.FIAT,
                2,
                NormalBalance.DEBIT,
                balance,
                LedgerAccountStatus.ACTIVE);
    }

    private static LedgerAccount holdAccount() {
        return new LedgerAccount(
                HOLD_ACCOUNT_ID,
                Mode.TEST,
                LedgerAccountRole.TENANT,
                "org_1",
                "mer_1",
                null,
                LedgerAccountType.PREPAID_CARD_HOLD,
                "USD",
                AssetClass.FIAT,
                2,
                NormalBalance.DEBIT,
                BigDecimal.ZERO,
                LedgerAccountStatus.ACTIVE);
    }
}
