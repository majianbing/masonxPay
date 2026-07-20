package com.masonx.virtualaccount.domain;

import com.masonx.common.error.BusinessException;
import com.masonx.common.tenant.MerchantId;
import com.masonx.common.tenant.Mode;
import com.masonx.common.tenant.TenantRef;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.dto.RecordSettlementCommand;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for LedgerSettlementHandler against a real Postgres.
 *
 * Requires: docker compose up (in backend/virtual-account-service/)
 * Run with: mvn test -Pintegration -pl virtual-account-service -am
 *
 * Data is LEFT in the DB after each test so you can inspect it with a DB client.
 * Cleanup runs AFTER each test (not before), so the DB has the last test's state.
 *
 * Connect: psql -h localhost -p 5442 -U pay_app_user -d msx_virtual_account_test
 */
@SpringBootTest(properties = {
        "va.kafka.consumer.enabled=false",
        "spring.kafka.bootstrap-servers=localhost:9999"
})
@ActiveProfiles("test")
class LedgerSettlementHandlerIntegrationTest {

    @Autowired LedgerSettlementHandler handler;
    @Autowired LedgerAccountRepository       accountRepo;
    @Autowired JdbcTemplate            jdbc;

    private UUID      merchantUuid;
    private String    merchantId;
    private LedgerAccount tenantCash;
    private LedgerAccount externalClearing;
    private LedgerAccount platformFee;

    @BeforeEach
    void setUp() {
        cleanDb();

        merchantUuid = UUID.randomUUID();
        merchantId   = merchantUuid.toString();

        tenantCash = new LedgerAccount(
                "ac_test_cash_1", Mode.TEST, LedgerAccountRole.TENANT,
                "org_1", merchantId, null,
                LedgerAccountType.CASH, "USD", AssetClass.FIAT, 2,
                NormalBalance.DEBIT, BigDecimal.ZERO, LedgerAccountStatus.ACTIVE);

        externalClearing = new LedgerAccount(
                "ac_test_ext_1", Mode.TEST, LedgerAccountRole.EXTERNAL,
                null, null, "stripe",
                LedgerAccountType.CLEARING, "USD", AssetClass.FIAT, 2,
                NormalBalance.CREDIT, BigDecimal.ZERO, LedgerAccountStatus.ACTIVE);

        platformFee = new LedgerAccount(
                "ac_test_fee_1", Mode.TEST, LedgerAccountRole.PLATFORM,
                null, null, null,
                LedgerAccountType.PLATFORM_FEE_RECEIVABLE, "USD", AssetClass.FIAT, 2,
                NormalBalance.DEBIT, BigDecimal.ZERO, LedgerAccountStatus.ACTIVE);

        accountRepo.save(tenantCash);
        accountRepo.save(externalClearing);
    }

    /** Runs AFTER each test — data stays in DB during the test so you can inspect it. */
    @AfterEach
    void tearDown() {
        // intentionally left empty — call cleanDb() manually here if you want cleanup after each test
        // cleanDb();
    }

    private void cleanDb() {
        for (int i = 0; i < 64; i++) jdbc.execute("DELETE FROM va_ledger_entry_" + i);
        for (int i = 0; i < 8;  i++) jdbc.execute("DELETE FROM va_inbox_event_" + i);
        jdbc.execute("DELETE FROM ledger_account");
    }

    // --- helpers ---

    private TenantRef tenant() {
        return new TenantRef(Mode.TEST, null, new MerchantId(merchantUuid));
    }

    private RecordSettlementCommand settlement(String eventId, BigDecimal amount,
                                               BigDecimal feeAmount, Direction dir) {
        BigDecimal net = amount.subtract(feeAmount);
        return new RecordSettlementCommand(
                eventId, tenant(), UUID.randomUUID(), "stripe",
                amount, feeAmount, net, "USD", AssetClass.FIAT, 2, dir);
    }

    private BigDecimal balanceOf(String ledgerAccountId) {
        return accountRepo.findById(ledgerAccountId).orElseThrow().balance();
    }

    private int ledgerEntryCount(String ledgerAccountId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM va_ledger_entry WHERE ledger_account_id = ?",
                Integer.class, ledgerAccountId);
    }

    // --- tests ---

    @Test
    void two_entry_net_settlement_increases_tenant_balance() {
        handler.handle(settlement("evt_001", new BigDecimal("100.00"), BigDecimal.ZERO, Direction.CREDIT));

        assertThat(balanceOf(tenantCash.ledgerAccountId())).isEqualByComparingTo("100.00");
        assertThat(balanceOf(externalClearing.ledgerAccountId())).isEqualByComparingTo("100.00");
        assertThat(ledgerEntryCount(tenantCash.ledgerAccountId())).isEqualTo(1);
        assertThat(ledgerEntryCount(externalClearing.ledgerAccountId())).isEqualTo(1);
    }

    @Test
    void three_entry_settlement_routes_fee_to_platform_account() {
        accountRepo.save(platformFee);

        handler.handle(settlement("evt_002", new BigDecimal("100.00"), new BigDecimal("5.00"), Direction.CREDIT));

        assertThat(balanceOf(tenantCash.ledgerAccountId())).isEqualByComparingTo("95.00");
        assertThat(balanceOf(platformFee.ledgerAccountId())).isEqualByComparingTo("5.00");
        assertThat(balanceOf(externalClearing.ledgerAccountId())).isEqualByComparingTo("100.00");
        assertThat(ledgerEntryCount(tenantCash.ledgerAccountId())).isEqualTo(1);
        assertThat(ledgerEntryCount(platformFee.ledgerAccountId())).isEqualTo(1);
        assertThat(ledgerEntryCount(externalClearing.ledgerAccountId())).isEqualTo(1);
    }

    @Test
    void fee_without_platform_account_falls_back_to_net_two_entry() {
        handler.handle(settlement("evt_003", new BigDecimal("100.00"), new BigDecimal("5.00"), Direction.CREDIT));

        assertThat(balanceOf(tenantCash.ledgerAccountId())).isEqualByComparingTo("95.00");
        assertThat(balanceOf(externalClearing.ledgerAccountId())).isEqualByComparingTo("95.00");
        assertThat(ledgerEntryCount(tenantCash.ledgerAccountId())).isEqualTo(1);
    }

    @Test
    void refund_reversal_decreases_tenant_balance() {
        handler.handle(settlement("evt_fund",   new BigDecimal("200.00"), BigDecimal.ZERO, Direction.CREDIT));
        handler.handle(settlement("evt_refund", new BigDecimal("80.00"),  BigDecimal.ZERO, Direction.DEBIT));

        assertThat(balanceOf(tenantCash.ledgerAccountId())).isEqualByComparingTo("120.00");
        assertThat(balanceOf(externalClearing.ledgerAccountId())).isEqualByComparingTo("120.00");
    }

    @Test
    void zero_amount_is_skipped() {
        handler.handle(settlement("evt_zero", BigDecimal.ZERO, BigDecimal.ZERO, Direction.CREDIT));

        assertThat(balanceOf(tenantCash.ledgerAccountId())).isEqualByComparingTo("0.00");
        assertThat(ledgerEntryCount(tenantCash.ledgerAccountId())).isEqualTo(0);
    }

    @Test
    void missing_tenant_account_throws_business_exception() {
        var cmd = new RecordSettlementCommand(
                "evt_miss", new TenantRef(Mode.TEST, null, new MerchantId(UUID.randomUUID())),
                UUID.randomUUID(), "stripe",
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"),
                "USD", AssetClass.FIAT, 2, Direction.CREDIT);

        assertThatThrownBy(() -> handler.handle(cmd))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No CASH account");
    }

    @Test
    void missing_external_account_throws_business_exception() {
        var cmd = new RecordSettlementCommand(
                "evt_noext", tenant(), UUID.randomUUID(),
                "unknown_provider",
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"),
                "USD", AssetClass.FIAT, 2, Direction.CREDIT);

        assertThatThrownBy(() -> handler.handle(cmd))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No CLEARING account");
    }

    @Test
    void sequential_settlements_accumulate_balance_and_chain_signatures() {
        handler.handle(settlement("evt_s1", new BigDecimal("100.00"), BigDecimal.ZERO, Direction.CREDIT));
        handler.handle(settlement("evt_s2", new BigDecimal("50.00"),  BigDecimal.ZERO, Direction.CREDIT));

        assertThat(balanceOf(tenantCash.ledgerAccountId())).isEqualByComparingTo("150.00");
        assertThat(ledgerEntryCount(tenantCash.ledgerAccountId())).isEqualTo(2);
    }
}
