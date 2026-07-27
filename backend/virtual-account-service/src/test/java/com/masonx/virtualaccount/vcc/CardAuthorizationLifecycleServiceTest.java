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
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.ledger.posting.CardAuthHoldReleasePostingRule;
import com.masonx.virtualaccount.domain.po.CardAuthorization;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import com.masonx.virtualaccount.vcc.dto.IssuerAuthReversalRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardAuthorizationLifecycleServiceTest {

    private static final String ISSUER_ID = "RAIL_SIM";
    private static final String AUTHORIZATION_ID = "auth_abc123";
    private static final String CARD_ID = "card_1";
    private static final String CARD_ACCOUNT_ID = "ac_card";
    private static final String HOLD_ACCOUNT_ID = "ac_hold";
    private static final Instant NOW = Instant.parse("2026-07-26T08:00:00Z");

    @Mock CardAuthorizationRepository authorizationRepo;
    @Mock VirtualCardRepository virtualCardRepo;
    @Mock com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository accountRepo;
    @Mock LedgerFacade ledger;
    @Mock SnowflakeIdGenerator idGen;

    CardAuthorizationLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new CardAuthorizationLifecycleService(
                authorizationRepo,
                virtualCardRepo,
                accountRepo,
                ledger,
                new CardAuthHoldReleasePostingRule(idGen),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void reverse_full_hold_posts_release_and_marks_reversed() {
        when(authorizationRepo.findByIssuerIdAndAuthorizationIdForUpdate(ISSUER_ID, AUTHORIZATION_ID))
                .thenReturn(Optional.of(auth(new BigDecimal("25.00"), BigDecimal.ZERO)));
        mockCardAndAccounts();
        when(idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix())).thenReturn("tx_rail_1");
        when(ledger.postAllIfNew(any(), any(), eq("card-auth-hold-release"))).thenReturn(true);

        var response = service.reverse(ISSUER_ID, new IssuerAuthReversalRequest(
                AUTHORIZATION_ID, "rev_1", null, "USD"));

        assertThat(response.result()).isEqualTo(CardAuthorizationLifecycleService.RESULT_RELEASED);
        assertThat(response.releasedAmount()).isEqualByComparingTo("25.00");
        assertThat(response.remainingHold()).isEqualByComparingTo("0.00");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerPostingCommand>> txCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(ledger).postAllIfNew(txCaptor.capture(), eventCaptor.capture(), eq("card-auth-hold-release"));
        assertThat(eventCaptor.getValue()).startsWith("card_hold_rel_");
        assertThat(txCaptor.getValue().get(0).entries())
                .anySatisfy(entry -> {
                    assertThat(entry.ledgerAccountId()).isEqualTo(HOLD_ACCOUNT_ID);
                    assertThat(entry.direction()).isEqualTo(Direction.DEBIT);
                })
                .anySatisfy(entry -> {
                    assertThat(entry.ledgerAccountId()).isEqualTo(CARD_ACCOUNT_ID);
                    assertThat(entry.direction()).isEqualTo(Direction.CREDIT);
                });
        verify(authorizationRepo).recordHoldRelease("cauth_1", new BigDecimal("25.00"),
                CardAuthorizationStatus.REVERSED, "REVERSAL", NOW);
    }

    @Test
    void reverse_partial_hold_keeps_authorization_open() {
        when(authorizationRepo.findByIssuerIdAndAuthorizationIdForUpdate(ISSUER_ID, AUTHORIZATION_ID))
                .thenReturn(Optional.of(auth(new BigDecimal("25.00"), BigDecimal.ZERO)));
        mockCardAndAccounts();
        when(idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix())).thenReturn("tx_rail_1");
        when(ledger.postAllIfNew(any(), any(), eq("card-auth-hold-release"))).thenReturn(true);

        var response = service.reverse(ISSUER_ID, new IssuerAuthReversalRequest(
                AUTHORIZATION_ID, "rev_1", new BigDecimal("5.00"), "USD"));

        assertThat(response.releasedAmount()).isEqualByComparingTo("5.00");
        assertThat(response.remainingHold()).isEqualByComparingTo("20.00");
        verify(authorizationRepo).recordHoldRelease("cauth_1", new BigDecimal("5.00"),
                CardAuthorizationStatus.AUTHORIZED, "REVERSAL", NOW);
    }

    @Test
    void reverse_rejects_release_amount_above_remaining_hold() {
        when(authorizationRepo.findByIssuerIdAndAuthorizationIdForUpdate(ISSUER_ID, AUTHORIZATION_ID))
                .thenReturn(Optional.of(auth(new BigDecimal("25.00"), new BigDecimal("20.00"))));

        var response = service.reverse(ISSUER_ID, new IssuerAuthReversalRequest(
                AUTHORIZATION_ID, "rev_2", new BigDecimal("10.00"), "USD"));

        assertThat(response.result()).isEqualTo(CardAuthorizationLifecycleService.RESULT_NOT_OPEN);
        assertThat(response.reason())
                .isEqualTo(CardAuthorizationLifecycleService.REASON_RELEASE_AMOUNT_EXCEEDS_HOLD);
        verify(ledger, never()).postAllIfNew(any(), any(), any());
        verify(authorizationRepo, never()).recordHoldRelease(any(), any(), any(), any(), any());
    }

    @Test
    void expireStaleHolds_releases_remaining_hold_and_marks_expired() {
        when(authorizationRepo.findAuthorizedBeforeForUpdate(NOW.minus(Duration.ofDays(7)), 10))
                .thenReturn(List.of(auth(new BigDecimal("25.00"), new BigDecimal("5.00"))));
        mockCardAndAccounts();
        when(idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix())).thenReturn("tx_rail_1");
        when(ledger.postAllIfNew(any(), any(), eq("card-auth-hold-release"))).thenReturn(true);

        int released = service.expireStaleHolds(Duration.ofDays(7), 10);

        assertThat(released).isEqualTo(1);
        verify(authorizationRepo).recordHoldRelease("cauth_1", new BigDecimal("25.00"),
                CardAuthorizationStatus.EXPIRED, "EXPIRY", NOW);
    }

    @Test
    void releaseEventId_is_deterministic_and_scoped_by_release_id() {
        String first = CardAuthorizationLifecycleService.releaseEventId(ISSUER_ID, AUTHORIZATION_ID,
                "rev_1", "REVERSAL");
        String second = CardAuthorizationLifecycleService.releaseEventId(ISSUER_ID, AUTHORIZATION_ID,
                "rev_1", "REVERSAL");
        String other = CardAuthorizationLifecycleService.releaseEventId(ISSUER_ID, AUTHORIZATION_ID,
                "rev_2", "REVERSAL");

        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("card_hold_rel_");
        assertThat(first.length()).isLessThanOrEqualTo(64);
        assertThat(other).isNotEqualTo(first);
    }

    private void mockCardAndAccounts() {
        when(virtualCardRepo.findById(CARD_ID)).thenReturn(Optional.of(card()));
        when(accountRepo.findByIdForUpdate(CARD_ACCOUNT_ID)).thenReturn(Optional.of(cardAccount()));
        when(accountRepo.findByIdForUpdate(HOLD_ACCOUNT_ID)).thenReturn(Optional.of(holdAccount()));
    }

    private static CardAuthorization auth(BigDecimal amount, BigDecimal releasedAmount) {
        return new CardAuthorization(
                "cauth_1",
                ISSUER_ID,
                AUTHORIZATION_ID,
                CARD_ID,
                "123456",
                "654321",
                amount,
                "USD",
                "APPROVED",
                null,
                "card_auth_1",
                CardAuthorizationStatus.AUTHORIZED,
                releasedAmount,
                null,
                null,
                BigDecimal.ZERO,
                null,
                NOW.minus(Duration.ofDays(8)));
    }

    private static VirtualCard card() {
        return new VirtualCard(
                CARD_ID,
                "ctok_abc123",
                "999999****1234",
                "999999",
                CARD_ACCOUNT_ID,
                HOLD_ACCOUNT_ID,
                "ac_owner",
                "cprog_1",
                "ip_1",
                "ch_1",
                "railsim_ctok_abc123",
                "ctok_abc123",
                com.masonx.virtualaccount.domain.constant.VirtualCardStatus.ACTIVE,
                null,
                "USD",
                LocalDate.of(2027, 1, 1),
                NOW,
                NOW);
    }

    private static LedgerAccount cardAccount() {
        return account(CARD_ACCOUNT_ID, LedgerAccountType.PREPAID_CARD);
    }

    private static LedgerAccount holdAccount() {
        return account(HOLD_ACCOUNT_ID, LedgerAccountType.PREPAID_CARD_HOLD);
    }

    private static LedgerAccount account(String accountId, LedgerAccountType type) {
        return new LedgerAccount(
                accountId,
                Mode.TEST,
                LedgerAccountRole.TENANT,
                "org_1",
                "mer_1",
                null,
                type,
                "USD",
                AssetClass.FIAT,
                2,
                NormalBalance.CREDIT,
                BigDecimal.ZERO,
                LedgerAccountStatus.ACTIVE);
    }
}
