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
import com.masonx.virtualaccount.domain.ledger.AccountingEntryDraft;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostingRulesTest {

    private static final String EVENT_ID = "evt_1";
    private static final String MERCHANT_ID = "mer_1";
    private static final String ORG_ID = "org_1";

    @Test
    void vccFunding_moves_liability_from_wallet_to_card() {
        SnowflakeIdGenerator idGen = idGen("tx_fund");
        var rule = new VccFundingPostingRule(idGen);

        LedgerPostingCommand command = rule.build(new VccFundingPostingRule.FundingEvent(
                card(), account("ac_owner", LedgerAccountType.WALLET), amount("25.00"), EVENT_ID)).get(0);

        assertThat(command.transactionId()).isEqualTo("tx_fund");
        assertThat(command.entryType()).isEqualTo(TransactionType.INTERNAL);
        assertLeg(command, "ac_owner", Direction.DEBIT, "25.00", EVENT_ID);
        assertLeg(command, "ac_card", Direction.CREDIT, "25.00", EVENT_ID);
    }

    @Test
    void vccCloseSweep_moves_liability_from_card_back_to_wallet() {
        SnowflakeIdGenerator idGen = idGen("tx_close");
        var rule = new VccCloseSweepPostingRule(idGen);

        LedgerPostingCommand command = rule.build(new VccCloseSweepPostingRule.CloseSweepEvent(
                card(), account("ac_owner", LedgerAccountType.WALLET), amount("12.00"), "vcc_close_card_1")).get(0);

        assertThat(command.transactionId()).isEqualTo("tx_close");
        assertLeg(command, "ac_card", Direction.DEBIT, "12.00", "vcc_close_card_1");
        assertLeg(command, "ac_owner", Direction.CREDIT, "12.00", "vcc_close_card_1");
    }

    @Test
    void authHold_moves_liability_from_available_to_held() {
        SnowflakeIdGenerator idGen = idGen("tx_auth");
        var rule = new CardAuthHoldPostingRule(idGen);

        LedgerPostingCommand command = rule.build(new CardAuthHoldPostingRule.AuthHoldEvent(
                card(), account("ac_card", LedgerAccountType.PREPAID_CARD),
                account("ac_hold", LedgerAccountType.PREPAID_CARD_HOLD),
                amount("10.00"), "USD", EVENT_ID)).get(0);

        assertLeg(command, "ac_card", Direction.DEBIT, "10.00", EVENT_ID);
        assertLeg(command, "ac_hold", Direction.CREDIT, "10.00", EVENT_ID);
    }

    @Test
    void cardSettlement_sale_extinguishes_hold_into_network_obligation() {
        SnowflakeIdGenerator idGen = idGen("tx_sale");
        var rule = new CardSettlementPostingRule(idGen);

        LedgerPostingCommand command = rule.buildSale(new CardSettlementPostingRule.SaleEvent(
                railEvent(MoneyMovementType.CARD_SALE, "100.00"), EVENT_ID, card(),
                account("ac_card", LedgerAccountType.PREPAID_CARD),
                account("ac_hold", LedgerAccountType.PREPAID_CARD_HOLD),
                account("ac_receivable", LedgerAccountType.CARD_NETWORK_RECEIVABLE))).get(0);

        assertThat(command.entryType()).isEqualTo(TransactionType.CARD_SALE);
        assertLeg(command, "ac_hold", Direction.DEBIT, "100.00", EVENT_ID);
        assertLeg(command, "ac_receivable", Direction.CREDIT, "100.00", EVENT_ID);
    }

    @Test
    void cardSettlement_reversal_releases_hold_back_to_available() {
        SnowflakeIdGenerator idGen = idGen("tx_reversal");
        var rule = new CardSettlementPostingRule(idGen);

        LedgerPostingCommand command = rule.buildReversal(new CardSettlementPostingRule.ReversalEvent(
                railEvent(MoneyMovementType.CARD_REVERSAL, "40.00"), EVENT_ID, card(),
                account("ac_card", LedgerAccountType.PREPAID_CARD),
                account("ac_hold", LedgerAccountType.PREPAID_CARD_HOLD))).get(0);

        assertThat(command.entryType()).isEqualTo(TransactionType.REVERSAL);
        assertLeg(command, "ac_hold", Direction.DEBIT, "40.00", EVENT_ID);
        assertLeg(command, "ac_card", Direction.CREDIT, "40.00", EVENT_ID);
    }

    @Test
    void railSettlement_bankTransferAndReturn_are_opposite_journals_when_covered() {
        SnowflakeIdGenerator idGen = idGen("tx_rail");
        var rule = new RailSettlementPostingRule(idGen);
        LedgerAccount wallet = account("ac_wallet", LedgerAccountType.WALLET, amount("500.00"));
        LedgerAccount receivable = account("ac_receivable", LedgerAccountType.BANK_RAIL_RECEIVABLE);

        LedgerPostingCommand transfer = rule.buildBankTransfer(new RailSettlementPostingRule.BankEvent(
                railEvent(MoneyMovementType.BANK_CREDIT_TRANSFER, "200.00"),
                EVENT_ID, wallet, receivable, null)).get(0);
        LedgerPostingCommand returned = rule.buildBankReturn(new RailSettlementPostingRule.BankEvent(
                railEvent(MoneyMovementType.BANK_RETURN, "200.00"),
                EVENT_ID, wallet, receivable, null)).get(0);

        assertLeg(transfer, "ac_receivable", Direction.DEBIT, "200.00", EVENT_ID);
        assertLeg(transfer, "ac_wallet", Direction.CREDIT, "200.00", EVENT_ID);
        assertLeg(returned, "ac_wallet", Direction.DEBIT, "200.00", EVENT_ID);
        assertLeg(returned, "ac_receivable", Direction.CREDIT, "200.00", EVENT_ID);
    }

    @Test
    void bankReturn_shortfall_splits_between_wallet_and_merchant_receivable() {
        var rule = new RailSettlementPostingRule(idGen("tx_rail"));
        LedgerAccount wallet = account("ac_wallet", LedgerAccountType.WALLET, amount("120.00"));
        LedgerAccount receivable = account("ac_receivable", LedgerAccountType.BANK_RAIL_RECEIVABLE);
        LedgerAccount debt = account("ac_debt", LedgerAccountType.MERCHANT_RECEIVABLE);

        LedgerPostingCommand command = rule.buildBankReturn(new RailSettlementPostingRule.BankEvent(
                railEvent(MoneyMovementType.BANK_RETURN, "200.00"),
                EVENT_ID, wallet, receivable, debt)).get(0);

        assertThat(command.entries()).hasSize(3);
        assertLeg(command, "ac_wallet", Direction.DEBIT, "120.00", EVENT_ID);
        assertLeg(command, "ac_debt", Direction.DEBIT, "80.00", EVENT_ID);
        assertLeg(command, "ac_receivable", Direction.CREDIT, "200.00", EVENT_ID);
    }

    @Test
    void bankReturn_empty_wallet_books_full_amount_as_merchant_debt() {
        var rule = new RailSettlementPostingRule(idGen("tx_rail"));
        LedgerAccount wallet = account("ac_wallet", LedgerAccountType.WALLET, amount("0.00"));
        LedgerAccount receivable = account("ac_receivable", LedgerAccountType.BANK_RAIL_RECEIVABLE);
        LedgerAccount debt = account("ac_debt", LedgerAccountType.MERCHANT_RECEIVABLE);

        LedgerPostingCommand command = rule.buildBankReturn(new RailSettlementPostingRule.BankEvent(
                railEvent(MoneyMovementType.BANK_RETURN, "200.00"),
                EVENT_ID, wallet, receivable, debt)).get(0);

        assertThat(command.entries()).hasSize(2);
        assertLeg(command, "ac_debt", Direction.DEBIT, "200.00", EVENT_ID);
        assertLeg(command, "ac_receivable", Direction.CREDIT, "200.00", EVENT_ID);
    }

    @Test
    void bankReturn_shortfall_without_receivable_account_is_a_bug() {
        var rule = new RailSettlementPostingRule(idGen("tx_rail"));
        LedgerAccount wallet = account("ac_wallet", LedgerAccountType.WALLET, amount("50.00"));
        LedgerAccount receivable = account("ac_receivable", LedgerAccountType.BANK_RAIL_RECEIVABLE);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                rule.buildBankReturn(new RailSettlementPostingRule.BankEvent(
                        railEvent(MoneyMovementType.BANK_RETURN, "200.00"),
                        EVENT_ID, wallet, receivable, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bankTransfer_recoups_open_merchant_debt_before_crediting_wallet() {
        var rule = new RailSettlementPostingRule(idGen("tx_rail"));
        LedgerAccount wallet = account("ac_wallet", LedgerAccountType.WALLET, amount("0.00"));
        LedgerAccount receivable = account("ac_receivable", LedgerAccountType.BANK_RAIL_RECEIVABLE);
        LedgerAccount debt = account("ac_debt", LedgerAccountType.MERCHANT_RECEIVABLE, amount("80.00"));

        LedgerPostingCommand command = rule.buildBankTransfer(new RailSettlementPostingRule.BankEvent(
                railEvent(MoneyMovementType.BANK_CREDIT_TRANSFER, "200.00"),
                EVENT_ID, wallet, receivable, debt)).get(0);

        assertThat(command.entries()).hasSize(3);
        assertLeg(command, "ac_receivable", Direction.DEBIT, "200.00", EVENT_ID);
        assertLeg(command, "ac_debt", Direction.CREDIT, "80.00", EVENT_ID);
        assertLeg(command, "ac_wallet", Direction.CREDIT, "120.00", EVENT_ID);
    }

    @Test
    void bankTransfer_full_recoupment_omits_wallet_leg() {
        var rule = new RailSettlementPostingRule(idGen("tx_rail"));
        LedgerAccount wallet = account("ac_wallet", LedgerAccountType.WALLET, amount("0.00"));
        LedgerAccount receivable = account("ac_receivable", LedgerAccountType.BANK_RAIL_RECEIVABLE);
        LedgerAccount debt = account("ac_debt", LedgerAccountType.MERCHANT_RECEIVABLE, amount("500.00"));

        LedgerPostingCommand command = rule.buildBankTransfer(new RailSettlementPostingRule.BankEvent(
                railEvent(MoneyMovementType.BANK_CREDIT_TRANSFER, "200.00"),
                EVENT_ID, wallet, receivable, debt)).get(0);

        assertThat(command.entries()).hasSize(2);
        assertLeg(command, "ac_receivable", Direction.DEBIT, "200.00", EVENT_ID);
        assertLeg(command, "ac_debt", Direction.CREDIT, "200.00", EVENT_ID);
    }

    @Test
    void gatewaySettlement_withPlatformFee_splits_net_fee_and_gross_clearing() {
        LedgerAccountRepository accountRepo = mock(LedgerAccountRepository.class);
        SnowflakeIdGenerator idGen = idGen("tx_settlement");
        LedgerAccount platformFee = account("ac_fee", LedgerAccountType.PLATFORM_FEE_RECEIVABLE, LedgerAccountRole.PLATFORM);
        when(accountRepo.findPlatformAccount("USD", LedgerAccountType.PLATFORM_FEE_RECEIVABLE))
                .thenReturn(Optional.of(platformFee));
        var rule = new GatewaySettlementPostingRule(accountRepo, idGen);

        LedgerPostingCommand command = rule.build(new GatewaySettlementPostingRule.SettlementEvent(
                settlementCommand(), MERCHANT_ID,
                account("ac_cash", LedgerAccountType.CASH),
                account("ac_clearing", LedgerAccountType.CLEARING, LedgerAccountRole.EXTERNAL))).get(0);

        assertThat(command.entryType()).isEqualTo(TransactionType.SETTLEMENT);
        assertLeg(command, "ac_cash", Direction.DEBIT, "97.00", EVENT_ID, "merchant_net");
        assertLeg(command, "ac_fee", Direction.DEBIT, "3.00", EVENT_ID, "platform_fee");
        assertLeg(command, "ac_clearing", Direction.CREDIT, "100.00", EVENT_ID, "provider_gross");
    }

    @Test
    void gatewaySettlement_withPlatformFee_requires_fee_receivable_account() {
        LedgerAccountRepository accountRepo = mock(LedgerAccountRepository.class);
        when(accountRepo.findPlatformAccount("USD", LedgerAccountType.PLATFORM_FEE_RECEIVABLE))
                .thenReturn(Optional.empty());
        var rule = new GatewaySettlementPostingRule(accountRepo, idGen("tx_settlement"));

        assertThatThrownBy(() -> rule.build(new GatewaySettlementPostingRule.SettlementEvent(
                settlementCommand(), MERCHANT_ID,
                account("ac_cash", LedgerAccountType.CASH),
                account("ac_clearing", LedgerAccountType.CLEARING, LedgerAccountRole.EXTERNAL))))
                .hasMessageContaining("No PLATFORM_FEE_RECEIVABLE account");
    }

    private static void assertLeg(LedgerPostingCommand command, String ledgerAccountId,
                                  Direction direction, String amount, String sourceEventId) {
        assertLeg(command, ledgerAccountId, direction, amount, sourceEventId,
                AccountingEntryDraft.DEFAULT_SOURCE_EVENT_LEG);
    }

    private static void assertLeg(LedgerPostingCommand command, String ledgerAccountId,
                                  Direction direction, String amount, String sourceEventId,
                                  String sourceEventLeg) {
        assertThat(command.entries())
                .anySatisfy(entry -> {
                    assertThat(entry.ledgerAccountId()).isEqualTo(ledgerAccountId);
                    assertThat(entry.direction()).isEqualTo(direction);
                    assertThat(entry.amount()).isEqualByComparingTo(amount);
                    assertThat(entry.asset()).isEqualTo("USD");
                    assertThat(entry.sourceEventId()).isEqualTo(sourceEventId);
                    assertThat(entry.sourceEventLeg()).isEqualTo(sourceEventLeg);
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
        return account(id, type, LedgerAccountRole.TENANT, BigDecimal.ZERO);
    }

    private static LedgerAccount account(String id, LedgerAccountType type, BigDecimal balance) {
        return account(id, type, LedgerAccountRole.TENANT, balance);
    }

    private static LedgerAccount account(String id, LedgerAccountType type, LedgerAccountRole role) {
        return account(id, type, role, BigDecimal.ZERO);
    }

    private static LedgerAccount account(String id, LedgerAccountType type,
                                         LedgerAccountRole role, BigDecimal balance) {
        // Normal balance mirrors the production convention: fund-holding tenant
        // accounts are CREDIT-normal liabilities; receivables are DEBIT-normal.
        NormalBalance normal = switch (type) {
            case WALLET, PREPAID_CARD, PREPAID_CARD_HOLD, CLEARING, FEE_INCOME,
                 CARD_NETWORK_RECEIVABLE -> NormalBalance.CREDIT;
            default -> NormalBalance.DEBIT;
        };
        return new LedgerAccount(
                id, Mode.TEST, role, ORG_ID, MERCHANT_ID, "provider_1",
                type, "USD", AssetClass.FIAT, 2,
                normal, balance, LedgerAccountStatus.ACTIVE);
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
