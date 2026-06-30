package com.masonx.rail.adapter;

import com.masonx.contracts.rail.PaymentRail;
import com.masonx.rail.iso8583.Iso8583LogService;
import com.masonx.rail.iso8583.Iso8583NettyClient;
import com.masonx.rail.iso8583.Iso8583NettyClient;
import org.jpos.iso.packager.GenericPackager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for adapter BIN routing: verifies that each adapter correctly
 * claims (or rejects) a given rail + network combination.
 */
class Iso8583AdapterRoutingTest {

    @Mock GenericPackager    packager;
    @Mock Iso8583NettyClient client;
    @Mock Iso8583LogService  logService;

    private VisaSimIso8583Adapter        visaAdapter;
    private MastercardSimIso8583Adapter  mcAdapter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        visaAdapter = new VisaSimIso8583Adapter(packager, client, logService);
        mcAdapter   = new MastercardSimIso8583Adapter(packager, client, logService);
    }

    // ── VisaSim supports ──────────────────────────────────────────────────────

    @Test
    void visa_supports_cardRail_visaNetwork() {
        assertThat(visaAdapter.supports(PaymentRail.CARD_ISO8583, "VISA_SIM")).isTrue();
    }

    @Test
    void visa_doesNotSupport_mcNetwork() {
        assertThat(visaAdapter.supports(PaymentRail.CARD_ISO8583, "MC_SIM")).isFalse();
    }

    @Test
    void visa_doesNotSupport_bankRail() {
        assertThat(visaAdapter.supports(PaymentRail.BANK_ISO20022, "VISA_SIM")).isFalse();
    }

    // ── MastercardSim supports ────────────────────────────────────────────────

    @Test
    void mc_supports_cardRail_mcNetwork() {
        assertThat(mcAdapter.supports(PaymentRail.CARD_ISO8583, "MC_SIM")).isTrue();
    }

    @Test
    void mc_doesNotSupport_visaNetwork() {
        assertThat(mcAdapter.supports(PaymentRail.CARD_ISO8583, "VISA_SIM")).isFalse();
    }

    @Test
    void mc_doesNotSupport_bankRail() {
        assertThat(mcAdapter.supports(PaymentRail.BANK_ISO20022, "MC_SIM")).isFalse();
    }

    // ── Network selection by PAN prefix ──────────────────────────────────────

    @Test
    void fourPrefix_mapsTo_visaSim() {
        assertThat(visaAdapter.supports(PaymentRail.CARD_ISO8583, resolveNetwork("4111111111110000"))).isTrue();
    }

    @Test
    void fivePrefix_mapsTo_mcSim() {
        assertThat(mcAdapter.supports(PaymentRail.CARD_ISO8583, resolveNetwork("5111111111110000"))).isTrue();
    }

    @Test
    void vabin999999_mapsTo_visaSim() {
        // BIN 999999 routes through VISA_SIM; the simulator internally calls VA issuer.
        assertThat(visaAdapter.supports(PaymentRail.CARD_ISO8583, resolveNetwork("9999990000001234"))).isTrue();
    }

    // ── network name accessors ────────────────────────────────────────────────

    @Test
    void visa_networkName_isVisaSim() {
        assertThat(visaAdapter.networkName()).isEqualTo("VISA_SIM");
    }

    @Test
    void mc_networkName_isMcSim() {
        assertThat(mcAdapter.networkName()).isEqualTo("MC_SIM");
    }

    @Test
    void visa_acquirerId_isDistinct() {
        assertThat(visaAdapter.acquirerId()).isNotEqualTo(mcAdapter.acquirerId());
    }

    // ── helper: mirrors RailPaymentController.resolveNetwork ─────────────────

    private static String resolveNetwork(String pan) {
        if (pan == null || pan.isBlank()) return "VISA_SIM";
        if (pan.startsWith("5")) return "MC_SIM";
        return "VISA_SIM";
    }
}
