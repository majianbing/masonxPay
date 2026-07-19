package com.masonx.virtualaccount.domain.ledger.posting;

import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.MerchantId;
import com.masonx.common.tenant.Mode;
import com.masonx.common.tenant.OrgId;
import com.masonx.common.tenant.TenantRef;
import com.masonx.contracts.EventEnvelope;
import com.masonx.contracts.rail.MoneyMovementType;
import com.masonx.contracts.rail.PaymentRail;
import com.masonx.contracts.rail.RailSettlementEvent;
import com.masonx.virtualaccount.domain.constant.AssetClass;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.LedgerAccountRole;
import com.masonx.virtualaccount.domain.constant.LedgerAccountStatus;
import com.masonx.virtualaccount.domain.constant.LedgerAccountType;
import com.masonx.virtualaccount.domain.constant.NormalBalance;
import com.masonx.virtualaccount.domain.constant.TransactionType;
import com.masonx.virtualaccount.domain.constant.VirtualCardStatus;
import com.masonx.virtualaccount.domain.dto.RecordSettlementCommand;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostingRulesTest {

    private static final String EVENT_ID = "evt_1";
    private static final String MERCHANT_ID = "mer_1";
    private static final String ORG_ID = "org_1";

    @Test
    void vccFunding_debits_card_and_credits_owner_wallet() {
        SnowflakeIdGenerator idGen = idGen("tx_fund");
        var rule = new VccFundingPostingRule(idGen);

        LedgerPostingCommand command = rule.build(new VccFundingPostingRule.FundingEvent(
                card(), account("ac_owner", LedgerAccountType.WALLET), amount("25.00"), EVENT_ID)).get(0);

        assertThat(command.transactionId()).isEqualTo("tx_fund");
        assertThat(command.entryType()).isEqualTo(TransactionType.INTERNAL);
        assertLeg(command, "ac_card", Direction.DEBIT, "25.00", EVENT_ID);
        assertLeg(command, "ac_owner", Direction.CREDIT, "25.00", EVENT_ID);
    }

    @Test
    void vccCloseSweep_debits_owner_wallet_and_credits_card() {
        SnowflakeIdGenerator idGen = idGen("tx_close");
        var rule = new VccCloseSweepPostingRule(idGen);

        LedgerPostingCommand command = rule.build(new VccCloseSweepPostingRule.CloseSweepEvent(
                card(), account("ac_owner", LedgerAccountType.WALLET), amount("12.00"))).get(0);

        assertThat(command.transactionId()).isEqualTo("tx_close");
        assertLeg(command, "ac_owner", Direction.DEBIT, "12.00", "tx_close");
        assertLeg(command, "ac_card", Direction.CREDIT, "12.00", "tx_close");
    }

    @Test
    void authHold_debits_hold_and_credits_card_available() {
        SnowflakeIdGenerator idGen = idGen("tx_auth");
        var rule = new CardAuthHoldPostingRule(idGen);

        LedgerPostingCommand command = rule.build(new CardAuthHoldPostingRule.AuthHoldEvent(
                card(), account("ac_card", LedgerAccountType.PREPAID_CARD),
                account("ac_hold", LedgerAccountType.PREPAID_CARD_HOLD),
                amount("10.00"), "USD", EVENT_ID)).get(0);

        assertLeg(command, "ac_hold", Direction.DEBIT, "10.00", EVENT_ID);
        assertLeg(command, "ac_card", Direction.CREDIT, "10.00", EVENT_ID);
    }

    @Test
    void cardSettlement_sale_debits_receivable_and_credits_hold() {
        SnowflakeIdGenerator idGen = idGen("tx_sale");
        var rule = new CardSettlementPostingRule(idGen);

        LedgerPostingCommand command = rule.buildSale(new CardSettlementPostingRule.SaleEvent(
                railEvent(MoneyMovementType.CARD_SALE, "100.00"), EVENT_ID, card(),
                account("ac_card", LedgerAccountType.PREPAID_CARD),
                account("ac_hold", LedgerAccountType.PREPAID_CARD_HOLD),
                account("ac_receivable", LedgerAccountType.CARD_NETWORK_RECEIVABLE))).get(0);

        assertThat(command.entryType()).isEqualTo(TransactionType.CARD_SALE);
        assertLeg(command, "ac_receivable", Direction.DEBIT, "100.00", EVENT_ID);
        assertLeg(command, "ac_hold", Direction.CREDIT, "100.00", EVENT_ID);
    }

    @Test
    void cardSettlement_reversal_debits_card_and_credits_hold() {
        SnowflakeIdGenerator idGen = idGen("tx_reversal");
        var rule = new CardSettlementPostingRule(idGen);

        LedgerPostingCommand command = rule.buildReversal(new CardSettlementPostingRule.ReversalEvent(
                railEvent(MoneyMovementType.CARD_REVERSAL, "40.00"), EVENT_ID, card(),
                account("ac_card", LedgerAccountType.PREPAID_CARD),
                account("ac_hold", LedgerAccountType.PREPAID_CARD_HOLD))).get(0);

        assertThat(command.entryType()).isEqualTo(TransactionType.REVERSAL);
        assertLeg(command, "ac_card", Direction.DEBIT, "40.00", EVENT_ID);
        assertLeg(command, "ac_hold", Direction.CREDIT, "40.00", EVENT_ID);
    }

    @Test
    void railSettlement_bankTransferAndReturn_are_opposite_journals() {
        SnowflakeIdGenerator idGen = idGen("tx_rail");
        var rule = new RailSettlementPostingRule(idGen);
        LedgerAccount wallet = account("ac_wallet", LedgerAccountType.WALLET);
        LedgerAccount receivable = account("ac_receivable", LedgerAccountType.BANK_RAIL_RECEIVABLE);

        LedgerPostingCommand transfer = rule.buildBankTransfer(new RailSettlementPostingRule.BankEvent(
                railEvent(MoneyMovementType.BANK_CREDIT_TRANSFER, "200.00"),
                EVENT_ID, wallet, receivable)).get(0);
        LedgerPostingCommand returned = rule.buildBankReturn(new RailSettlementPostingRule.BankEvent(
                railEvent(MoneyMovementType.BANK_RETURN, "200.00"),
                EVENT_ID, wallet, receivable)).get(0);

        assertLeg(transfer, "ac_receivable", Direction.DEBIT, "200.00", EVENT_ID);
        assertLeg(transfer, "ac_wallet", Direction.CREDIT, "200.00", EVENT_ID);
        assertLeg(returned, "ac_wallet", Direction.DEBIT, "200.00", EVENT_ID);
        assertLeg(returned, "ac_receivable", Direction.CREDIT, "200.00", EVENT_ID);
    }

    @Test
    void gatewaySettlement_withPlatformFee_splits_net_fee_and_gross_clearing() {
        LedgerAccountRepository accountRepo = mock(LedgerAccountRepository.class);
        SnowflakeIdGenerator idGen = idGen("tx_settlement");
        LedgerAccount platformFee = account("ac_fee", LedgerAccountType.FEE_INCOME, LedgerAccountRole.PLATFORM);
        when(accountRepo.findPlatformAccount("USD", LedgerAccountType.FEE_INCOME))
                .thenReturn(Optional.of(platformFee));
        var rule = new GatewaySettlementPostingRule(accountRepo, idGen);

        LedgerPostingCommand command = rule.build(new GatewaySettlementPostingRule.SettlementEvent(
                settlementCommand(), MERCHANT_ID,
                account("ac_cash", LedgerAccountType.CASH),
                account("ac_clearing", LedgerAccountType.CLEARING, LedgerAccountRole.EXTERNAL))).get(0);

        assertThat(command.entryType()).isEqualTo(TransactionType.SETTLEMENT);
        assertLeg(command, "ac_cash", Direction.DEBIT, "97.00", EVENT_ID);
        assertLeg(command, "ac_fee", Direction.DEBIT, "3.00", EVENT_ID);
        assertLeg(command, "ac_clearing", Direction.CREDIT, "100.00", EVENT_ID);
    }

    private static void assertLeg(LedgerPostingCommand command, String ledgerAccountId,
                                  Direction direction, String amount, String sourceEventId) {
        assertThat(command.entries())
                .anySatisfy(entry -> {
                    assertThat(entry.ledgerAccountId()).isEqualTo(ledgerAccountId);
                    assertThat(entry.direction()).isEqualTo(direction);
                    assertThat(entry.amount()).isEqualByComparingTo(amount);
                    assertThat(entry.asset()).isEqualTo("USD");
                    assertThat(entry.sourceEventId()).isEqualTo(sourceEventId);
                });
    }

    private static SnowflakeIdGenerator idGen(String transactionId) {
        SnowflakeIdGenerator idGen = mock(SnowflakeIdGenerator.class);
        when(idGen.generate(anyString())).thenReturn(transactionId);
        return idGen;
    }

    private static VirtualCard card() {
        return new VirtualCard(
                "card_1", "ctok_abc123", "999999****1234", "999999",
                "ac_card", "ac_hold", "ac_owner", VirtualCardStatus.ACTIVE,
                null, "USD", LocalDate.of(2027, 1, 1), Instant.now(), Instant.now());
    }

    private static LedgerAccount account(String id, LedgerAccountType type) {
        return account(id, type, LedgerAccountRole.TENANT);
    }

    private static LedgerAccount account(String id, LedgerAccountType type, LedgerAccountRole role) {
        return new LedgerAccount(
                id, Mode.TEST, role, ORG_ID, MERCHANT_ID, "provider_1",
                type, "USD", AssetClass.FIAT, 2,
                NormalBalance.DEBIT, BigDecimal.ZERO, LedgerAccountStatus.ACTIVE);
    }

    private static RailSettlementEvent railEvent(MoneyMovementType type, String amount) {
        var envelope = new EventEnvelope(EVENT_ID, RailSettlementEvent.TYPE, RailSettlementEvent.SCHEMA_VERSION,
                Instant.now(), "corr_1", null);
        return new RailSettlementEvent(
                envelope, "rail_payment_1", PaymentRail.CARD_ISO8583, type,
                "USD", amount(amount), null, null, "VISA_SIM", Instant.now(),
                MERCHANT_ID, "999999****1234", "ctok_abc123");
    }

    private static RecordSettlementCommand settlementCommand() {
        var tenant = new TenantRef(
                Mode.TEST,
                new OrgId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                new MerchantId(UUID.fromString("00000000-0000-0000-0000-000000000002")));
        return new RecordSettlementCommand(
                EVENT_ID, tenant, UUID.fromString("00000000-0000-0000-0000-000000000003"),
                "stripe", amount("100.00"), amount("3.00"), amount("97.00"),
                "USD", AssetClass.FIAT, 2, Direction.CREDIT);
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
