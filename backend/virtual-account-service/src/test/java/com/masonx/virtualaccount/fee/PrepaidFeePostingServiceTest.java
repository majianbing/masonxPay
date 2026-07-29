package com.masonx.virtualaccount.fee;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.AssetClass;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.LedgerAccountRole;
import com.masonx.virtualaccount.domain.constant.LedgerAccountStatus;
import com.masonx.virtualaccount.domain.constant.LedgerAccountType;
import com.masonx.virtualaccount.domain.constant.NormalBalance;
import com.masonx.virtualaccount.domain.constant.TransactionType;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.ledger.posting.PrepaidFeePostingRule;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessment;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessmentLine;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessmentSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrepaidFeePostingServiceTest {

    private static final String MERCHANT_ID = "mer_1";
    private static final String WALLET_ID = "ac_wallet";
    private static final String FEE_INCOME_ID = "va_platform_fee_income_usd";

    @Mock LedgerAccountRepository accountRepository;
    @Mock LedgerFacade ledger;
    @Mock SnowflakeIdGenerator idGenerator;

    @Test
    void postAssessmentFeesFromWallet_postsVisibleAndHiddenFeesWithStableEventKey() {
        PrepaidFeePostingService service = new PrepaidFeePostingService(
                accountRepository,
                ledger,
                new PrepaidFeePostingRule(idGenerator));
        when(accountRepository.findById(WALLET_ID)).thenReturn(Optional.of(wallet()));
        when(accountRepository.findPlatformAccount("USD", LedgerAccountType.FEE_INCOME))
                .thenReturn(Optional.of(feeIncomeAccount()));
        when(idGenerator.generate(MasonXIdPrefix.FEE_TRANSACTION.prefix())).thenReturn("tx_fee_1");
        when(ledger.postAllIfNew(any(), eq("fee:mer_1:TEST:CARD_CREATE:vc_1"), eq("prepaid-fee")))
                .thenReturn(true);

        boolean posted = service.postAssessmentFeesFromWallet(snapshot(), WALLET_ID);

        assertThat(posted).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerPostingCommand>> commandCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledger).postAllIfNew(commandCaptor.capture(), eq("fee:mer_1:TEST:CARD_CREATE:vc_1"),
                eq("prepaid-fee"));
        LedgerPostingCommand command = commandCaptor.getValue().get(0);
        assertThat(command.transactionId()).isEqualTo("tx_fee_1");
        assertThat(command.entryType()).isEqualTo(TransactionType.FEE);
        assertThat(command.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(command.entries()).hasSize(2);
        assertThat(command.entries().get(0).ledgerAccountId()).isEqualTo(WALLET_ID);
        assertThat(command.entries().get(0).direction()).isEqualTo(Direction.DEBIT);
        assertThat(command.entries().get(0).amount()).isEqualByComparingTo("1.60");
        assertThat(command.entries().get(0).sourceEventId()).isEqualTo("fee:mer_1:TEST:CARD_CREATE:vc_1");
        assertThat(command.entries().get(1).ledgerAccountId()).isEqualTo(FEE_INCOME_ID);
        assertThat(command.entries().get(1).direction()).isEqualTo(Direction.CREDIT);
        assertThat(command.entries().get(1).amount()).isEqualByComparingTo("1.60");
        assertThat(command.entries().get(1).sourceEventId()).isEqualTo("fee:mer_1:TEST:CARD_CREATE:vc_1");
    }

    private static PrepaidFeeAssessmentSnapshot snapshot() {
        Instant now = Instant.now();
        PrepaidFeeAssessment assessment = new PrepaidFeeAssessment(
                "feeas_1",
                MERCHANT_ID,
                Mode.TEST,
                "CARD_CREATE",
                "vc_1",
                "cprog_1",
                "vc_1",
                "fees_1",
                1,
                "{}",
                "[]",
                "{\"USD\":1.00}",
                "{\"USD\":0.60}",
                now);
        return new PrepaidFeeAssessmentSnapshot(assessment, List.of(
                line("MERCHANT_VISIBLE", "card_create_fixed", new BigDecimal("1.00"), now),
                line("PLATFORM_HIDDEN", "processor_hidden", new BigDecimal("0.60"), now)));
    }

    private static PrepaidFeeAssessmentLine line(String visibility, String name, BigDecimal amount, Instant now) {
        return new PrepaidFeeAssessmentLine(
                1L,
                "feeas_1",
                MERCHANT_ID,
                Mode.TEST,
                "rule_1",
                1,
                "Create card",
                name,
                name,
                visibility,
                "USD",
                amount,
                amount,
                amount,
                "HALF_UP",
                2,
                "{}",
                now);
    }

    private static LedgerAccount wallet() {
        return new LedgerAccount(
                WALLET_ID,
                Mode.TEST,
                LedgerAccountRole.TENANT,
                "org_1",
                MERCHANT_ID,
                null,
                LedgerAccountType.WALLET,
                "USD",
                AssetClass.FIAT,
                2,
                NormalBalance.CREDIT,
                new BigDecimal("10.00"),
                LedgerAccountStatus.ACTIVE);
    }

    private static LedgerAccount feeIncomeAccount() {
        return new LedgerAccount(
                FEE_INCOME_ID,
                Mode.TEST,
                LedgerAccountRole.PLATFORM,
                null,
                null,
                null,
                LedgerAccountType.FEE_INCOME,
                "USD",
                AssetClass.FIAT,
                2,
                NormalBalance.CREDIT,
                BigDecimal.ZERO,
                LedgerAccountStatus.ACTIVE);
    }
}
