package com.masonx.virtualaccount.vcc;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.AccountRole;
import com.masonx.virtualaccount.domain.constant.AccountStatus;
import com.masonx.virtualaccount.domain.constant.AccountType;
import com.masonx.virtualaccount.domain.constant.AssetClass;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.NormalBalance;
import com.masonx.virtualaccount.domain.constant.VirtualCardStatus;
import com.masonx.virtualaccount.domain.ledger.AccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.PostTransaction;
import com.masonx.virtualaccount.domain.po.VaAccount;
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
    @Mock AccountRepository accountRepo;
    @Mock LedgerFacade ledger;
    @Mock SnowflakeIdGenerator idGen;

    IssuerAuthService service;

    @BeforeEach
    void setUp() {
        service = new IssuerAuthService(virtualCardRepo, accountRepo, ledger, idGen);
    }

    @Test
    void authorize_posts_ledger_hold_journal_when_approved() {
        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(card()));
        when(accountRepo.findByIdForUpdate(CARD_ACCOUNT_ID)).thenReturn(Optional.of(cardAccount(new BigDecimal("100.00"))));
        when(accountRepo.findByIdForUpdate(HOLD_ACCOUNT_ID)).thenReturn(Optional.of(holdAccount()));
        when(idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix())).thenReturn("tx_rail_1");
        when(ledger.postIfNew(any(), any(), eq("vcc-card-auth"))).thenReturn(true);

        var response = service.authorize(request());

        assertThat(response.decision()).isEqualTo("APPROVED");
        ArgumentCaptor<PostTransaction> txCaptor = ArgumentCaptor.forClass(PostTransaction.class);
        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(ledger).postIfNew(txCaptor.capture(), eventCaptor.capture(), eq("vcc-card-auth"));

        assertThat(eventCaptor.getValue()).startsWith("vcc_auth_");
        assertThat(txCaptor.getValue().entries()).hasSize(2);
        assertThat(txCaptor.getValue().entries())
                .anySatisfy(entry -> {
                    assertThat(entry.accountId()).isEqualTo(HOLD_ACCOUNT_ID);
                    assertThat(entry.direction()).isEqualTo(Direction.DEBIT);
                    assertThat(entry.sourceEventId()).isEqualTo(eventCaptor.getValue());
                })
                .anySatisfy(entry -> {
                    assertThat(entry.accountId()).isEqualTo(CARD_ACCOUNT_ID);
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
        verify(ledger, never()).postIfNew(any(), any(), any());
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

    private static VaAccount cardAccount(BigDecimal balance) {
        return new VaAccount(
                CARD_ACCOUNT_ID,
                Mode.TEST,
                AccountRole.TENANT,
                "org_1",
                "mer_1",
                null,
                AccountType.PREPAID_CARD,
                "USD",
                AssetClass.FIAT,
                2,
                NormalBalance.DEBIT,
                balance,
                BigDecimal.ZERO,
                AccountStatus.ACTIVE);
    }

    private static VaAccount holdAccount() {
        return new VaAccount(
                HOLD_ACCOUNT_ID,
                Mode.TEST,
                AccountRole.TENANT,
                "org_1",
                "mer_1",
                null,
                AccountType.PREPAID_CARD_HOLD,
                "USD",
                AssetClass.FIAT,
                2,
                NormalBalance.DEBIT,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                AccountStatus.ACTIVE);
    }
}
