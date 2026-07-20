package com.masonx.virtualaccount.domain;

import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.MerchantId;
import com.masonx.common.tenant.Mode;
import com.masonx.common.tenant.OrgId;
import com.masonx.common.tenant.TenantRef;
import com.masonx.virtualaccount.domain.constant.AssetClass;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.LedgerAccountRole;
import com.masonx.virtualaccount.domain.constant.LedgerAccountStatus;
import com.masonx.virtualaccount.domain.constant.LedgerAccountType;
import com.masonx.virtualaccount.domain.constant.NormalBalance;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionReason;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionSource;
import com.masonx.virtualaccount.domain.dto.RecordSettlementCommand;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.posting.GatewaySettlementPostingRule;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerSettlementHandlerTest {

    private static final String EVENT_ID = "evt_settle_1";
    private static final UUID MERCHANT_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String MERCHANT_ID = MERCHANT_UUID.toString();

    @Mock LedgerAccountRepository accountRepo;
    @Mock LedgerFacade ledger;
    @Mock SettlementExceptionService settlementExceptions;

    LedgerSettlementHandler handler;

    @BeforeEach
    void setUp() {
        handler = new LedgerSettlementHandler(accountRepo, ledger,
                new GatewaySettlementPostingRule(accountRepo, new SnowflakeIdGenerator(0)),
                settlementExceptions);
    }

    @Test
    void handle_posts_settlement_when_accounts_exist() {
        when(accountRepo.findTenantAccount(MERCHANT_ID, Mode.TEST, "USD", LedgerAccountType.CASH))
                .thenReturn(Optional.of(cashAccount()));
        when(accountRepo.findExternalAccount("stripe", "USD", LedgerAccountType.CLEARING))
                .thenReturn(Optional.of(clearingAccount()));
        when(ledger.postAllIfNew(any(), eq(EVENT_ID), eq("settlement"))).thenReturn(true);

        handler.handle(command(new BigDecimal("100.00")));

        verify(ledger).postAllIfNew(any(), eq(EVENT_ID), eq("settlement"));
        verifyNoInteractions(settlementExceptions);
    }

    @Test
    void handle_parks_command_when_cash_account_missing() {
        when(accountRepo.findTenantAccount(MERCHANT_ID, Mode.TEST, "USD", LedgerAccountType.CASH))
                .thenReturn(Optional.empty());

        handler.handle(command(new BigDecimal("100.00")));

        verify(settlementExceptions).park(
                eq(SettlementExceptionSource.GATEWAY_SETTLEMENT), eq(EVENT_ID), anyString(),
                eq(SettlementExceptionReason.LEDGER_ACCOUNT_NOT_FOUND), anyString(), any());
        verifyNoInteractions(ledger);
    }

    @Test
    void handle_parks_command_when_amount_is_not_positive() {
        handler.handle(command(BigDecimal.ZERO));

        verify(settlementExceptions).park(
                eq(SettlementExceptionSource.GATEWAY_SETTLEMENT), eq(EVENT_ID), anyString(),
                eq(SettlementExceptionReason.INVALID_AMOUNT), anyString(), any());
        verifyNoInteractions(ledger, accountRepo);
    }

    @Test
    void handle_parks_command_when_fee_receivable_account_missing() {
        when(accountRepo.findTenantAccount(MERCHANT_ID, Mode.TEST, "USD", LedgerAccountType.CASH))
                .thenReturn(Optional.of(cashAccount()));
        when(accountRepo.findExternalAccount("stripe", "USD", LedgerAccountType.CLEARING))
                .thenReturn(Optional.of(clearingAccount()));
        when(accountRepo.findPlatformAccount("USD", LedgerAccountType.PLATFORM_FEE_RECEIVABLE))
                .thenReturn(Optional.empty());

        handler.handle(command(new BigDecimal("100.00"), new BigDecimal("3.00")));

        verify(settlementExceptions).park(
                eq(SettlementExceptionSource.GATEWAY_SETTLEMENT), eq(EVENT_ID), anyString(),
                eq(SettlementExceptionReason.LEDGER_ACCOUNT_NOT_FOUND), anyString(), any());
        verifyNoInteractions(ledger);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static RecordSettlementCommand command(BigDecimal amount) {
        return command(amount, BigDecimal.ZERO);
    }

    private static RecordSettlementCommand command(BigDecimal amount, BigDecimal feeAmount) {
        var tenant = new TenantRef(
                Mode.TEST,
                new OrgId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                new MerchantId(MERCHANT_UUID));
        BigDecimal netAmount = amount.subtract(feeAmount);
        return new RecordSettlementCommand(
                EVENT_ID, tenant, UUID.fromString("00000000-0000-0000-0000-000000000003"),
                "stripe", amount, feeAmount, netAmount,
                "USD", AssetClass.FIAT, 2, Direction.CREDIT);
    }

    private static LedgerAccount cashAccount() {
        return new LedgerAccount(
                "ac_cash", Mode.TEST, LedgerAccountRole.TENANT,
                "org_1", MERCHANT_ID, null,
                LedgerAccountType.CASH, "USD", AssetClass.FIAT, 2,
                NormalBalance.DEBIT, BigDecimal.ZERO, LedgerAccountStatus.ACTIVE);
    }

    private static LedgerAccount clearingAccount() {
        return new LedgerAccount(
                "ac_clearing", Mode.TEST, LedgerAccountRole.EXTERNAL,
                null, null, "stripe",
                LedgerAccountType.CLEARING, "USD", AssetClass.FIAT, 2,
                NormalBalance.CREDIT, BigDecimal.ZERO, LedgerAccountStatus.ACTIVE);
    }
}
