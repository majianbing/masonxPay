package com.masonx.virtualaccount.vcc;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.LedgerAccountRole;
import com.masonx.virtualaccount.domain.constant.LedgerAccountStatus;
import com.masonx.virtualaccount.domain.constant.LedgerAccountType;
import com.masonx.virtualaccount.domain.constant.AssetClass;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.NormalBalance;
import com.masonx.virtualaccount.domain.constant.VirtualCardStatus;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.ledger.posting.CardAuthHoldPostingRule;
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
class IssuerAuthServiceTest {

    private static final String CARD_ID = "card_1";
    private static final String MASKED_PAN = "999999****1234";
    private static final String CARD_ACCOUNT_ID = "ac_card";
    private static final String HOLD_ACCOUNT_ID = "ac_hold";

    @Mock VirtualCardRepository virtualCardRepo;
    @Mock LedgerAccountRepository accountRepo;
    @Mock LedgerFacade ledger;
    @Mock SnowflakeIdGenerator idGen;

    IssuerAuthService service;

    @BeforeEach
    void setUp() {
        service = new IssuerAuthService(virtualCardRepo, accountRepo, ledger,
                new CardAuthHoldPostingRule(idGen));
    }

    @Test
    void authorize_posts_ledger_hold_journal_when_approved() {
        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(card()));
        when(accountRepo.findByIdForUpdate(CARD_ACCOUNT_ID)).thenReturn(Optional.of(cardAccount(new BigDecimal("100.00"))));
        when(accountRepo.findByIdForUpdate(HOLD_ACCOUNT_ID)).thenReturn(Optional.of(holdAccount()));
        when(idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix())).thenReturn("tx_rail_1");
        when(ledger.postAllIfNew(any(), any(), eq("vcc-card-auth"))).thenReturn(true);

        var response = service.authorize(request());

        assertThat(response.decision()).isEqualTo("APPROVED");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerPostingCommand>> txCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(ledger).postAllIfNew(txCaptor.capture(), eventCaptor.capture(), eq("vcc-card-auth"));

        LedgerPostingCommand tx = txCaptor.getValue().get(0);
        assertThat(eventCaptor.getValue()).startsWith("vcc_auth_");
        assertThat(tx.entries()).hasSize(2);
        assertThat(tx.entries())
                .anySatisfy(entry -> {
                    assertThat(entry.ledgerAccountId()).isEqualTo(HOLD_ACCOUNT_ID);
                    assertThat(entry.direction()).isEqualTo(Direction.DEBIT);
                    assertThat(entry.sourceEventId()).isEqualTo(eventCaptor.getValue());
                })
                .anySatisfy(entry -> {
                    assertThat(entry.ledgerAccountId()).isEqualTo(CARD_ACCOUNT_ID);
                    assertThat(entry.direction()).isEqualTo(Direction.CREDIT);
                    assertThat(entry.sourceEventId()).isEqualTo(eventCaptor.getValue());
                });
    }

    @Test
    void authorize_declines_without_posting_when_available_balance_is_insufficient() {
        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(card()));
        when(accountRepo.findByIdForUpdate(CARD_ACCOUNT_ID)).thenReturn(Optional.of(cardAccount(new BigDecimal("10.00"))));
        when(accountRepo.findByIdForUpdate(HOLD_ACCOUNT_ID)).thenReturn(Optional.of(holdAccount()));

        var response = service.authorize(request());

        assertThat(response.decision()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("51");
        verify(ledger, never()).postAllIfNew(any(), any(), any());
    }

    private static IssuerAuthRequest request() {
        return new IssuerAuthRequest(MASKED_PAN, new BigDecimal("25.00"), "USD", "123456", "654321");
    }

    private static VirtualCard card() {
        return new VirtualCard(
                CARD_ID,
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
