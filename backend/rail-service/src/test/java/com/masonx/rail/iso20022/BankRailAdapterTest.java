package com.masonx.rail.iso20022;

import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.contracts.rail.MoneyMovementType;
import com.masonx.contracts.rail.PaymentRail;
import com.masonx.rail.adapter.SepaSimBankAdapter;
import com.masonx.rail.canonical.BankAccountRef;
import com.masonx.rail.canonical.CanonicalPaymentCommand;
import com.masonx.rail.canonical.RailPaymentStatus;
import com.masonx.rail.canonical.RailResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the ISO 20022 bank rail adapter (SEPA_SIM scenario matrix).
 *
 * <p>All HTTP calls are mocked. Tests exercise pain.002 parsing and the
 * canonical status mapping (ACCEPTED, DECLINED, FAILED).
 */
class BankRailAdapterTest {

    @Mock BankRailHttpClient   httpClient;
    @Mock Iso20022LogService   logService;

    private SepaSimBankAdapter adapter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        adapter = new SepaSimBankAdapter(httpClient, logService, new SnowflakeIdGenerator(0),
                "http://sim:9090");
    }

    // ── supports() ───────────────────────────────────────────────────────────

    @Test
    void sepa_supports_bankRail_sepaNetwork() {
        assertThat(adapter.supports(PaymentRail.BANK_ISO20022, "SEPA_SIM")).isTrue();
    }

    @Test
    void sepa_doesNotSupport_cardRail() {
        assertThat(adapter.supports(PaymentRail.CARD_ISO8583, "SEPA_SIM")).isFalse();
    }

    @Test
    void sepa_doesNotSupport_fednowNetwork() {
        assertThat(adapter.supports(PaymentRail.BANK_ISO20022, "FEDNOW_SIM")).isFalse();
    }

    // ── execute() — happy path ────────────────────────────────────────────────

    @Test
    void execute_accp_pain002_returns_ACCEPTED() {
        String accp = pain002Xml("ACCP", null);
        when(httpClient.sendPain001(anyString(), eq("http://sim:9090"))).thenReturn(accp);

        RailResponse response = adapter.execute(command("DE89370400440532013000"));

        assertThat(response.status()).isEqualTo(RailPaymentStatus.ACCEPTED);
        assertThat(response.responseCode()).isEqualTo("ACCP");
        assertThat(response.failureReason()).isNull();
    }

    // ── execute() — rejection ─────────────────────────────────────────────────

    @Test
    void execute_rjct_pain002_returns_DECLINED() {
        String rjct = pain002Xml("RJCT", "AC01");
        when(httpClient.sendPain001(anyString(), eq("http://sim:9090"))).thenReturn(rjct);

        RailResponse response = adapter.execute(command("DE89370400440532010001"));

        assertThat(response.status()).isEqualTo(RailPaymentStatus.DECLINED);
        assertThat(response.responseCode()).isEqualTo("RJCT");
        assertThat(response.failureReason()).contains("AC01");
    }

    // ── execute() — transport failure ─────────────────────────────────────────

    @Test
    void execute_httpFailure_returns_FAILED() {
        when(httpClient.sendPain001(anyString(), anyString()))
                .thenThrow(new RuntimeException("connection refused"));

        RailResponse response = adapter.execute(command("DE89370400440532013000"));

        assertThat(response.status()).isEqualTo(RailPaymentStatus.FAILED);
        assertThat(response.failureReason()).contains("connection refused");
    }

    // ── execute() — malformed response ───────────────────────────────────────

    @Test
    void execute_badXml_returns_FAILED() {
        when(httpClient.sendPain001(anyString(), anyString())).thenReturn("<not-valid-iso20022/>");

        RailResponse response = adapter.execute(command("DE89370400440532013000"));

        assertThat(response.status()).isEqualTo(RailPaymentStatus.FAILED);
        assertThat(response.failureReason()).isNotNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static CanonicalPaymentCommand command(String creditorIban) {
        return new CanonicalPaymentCommand(
                "rp_test_001", "merch_001", "idem_001",
                PaymentRail.BANK_ISO20022, MoneyMovementType.BANK_CREDIT_TRANSFER,
                new BigDecimal("100.00"), "EUR",
                null,
                new BankAccountRef("DE89370400440532013001", "DEUTDEDB", "Debtor Corp"),
                new BankAccountRef(creditorIban, "DEUTDEDB", "Creditor Corp"),
                null,
                Map.of("network", "SEPA_SIM")
        );
    }

    private static String pain002Xml(String txStatus, String reasonCode) {
        String rsn = reasonCode != null
                ? "<StsRsnInf><Rsn><Cd>" + reasonCode + "</Cd></Rsn></StsRsnInf>"
                : "";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.002.001.11">
                  <CstmrPmtStsRpt>
                    <GrpHdr><MsgId>TEST_PAIN002</MsgId><CreDtTm>2026-06-29T12:00:00</CreDtTm></GrpHdr>
                    <OrgnlGrpInfAndSts><OrgnlMsgId>TEST_MSG</OrgnlMsgId></OrgnlGrpInfAndSts>
                    <OrgnlPmtInfAndSts>
                      <TxInfAndSts>
                        <OrgnlEndToEndId>TEST_E2E</OrgnlEndToEndId>
                        <TxSts>%s</TxSts>
                        %s
                      </TxInfAndSts>
                    </OrgnlPmtInfAndSts>
                  </CstmrPmtStsRpt>
                </Document>
                """.formatted(txStatus, rsn);
    }
}
