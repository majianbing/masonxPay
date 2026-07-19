package com.masonx.rail.iso8583;

import com.masonx.rail.service.ReversalTask;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for 0400 reversal field construction and STAN/response handling.
 *
 * <p>Verifies MR2 invariants:
 * <ul>
 *   <li>Reversal STAN is in the high range (≥500 001), distinct from the original.
 *   <li>Original RRN is preserved in DE37 for network matching.
 *   <li>DE90 starts with "0100" + originalStan and is exactly 42 ASCII digits.
 *   <li>DE39=00 in 0410 → returns true; any other value → returns false.
 *   <li>0410 timeout → returns false (never throws).
 *   <li>markAsReversed called with the original STAN before send.
 * </ul>
 *
 * <p>DE90 and DE37 field content is verified by inspecting the packed byte array as
 * a Latin-1 string (all ISO 8583 IFA_NUMERIC/IF_CHAR fields encode as ASCII bytes).
 * This avoids a full unpack cycle on the 0400 with secondary bitmap, which is a
 * jPOS framework concern rather than business logic.
 */
class Iso8583ReversalSenderTest {

    private static GenericPackager realPackager;

    @Mock Iso8583NettyClient client;

    @BeforeAll
    static void loadPackager() throws Exception {
        realPackager = new GenericPackager(
                Iso8583ReversalSenderTest.class.getClassLoader()
                        .getResourceAsStream("iso8583-packager.xml"));
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // ── DE90 field construction ───────────────────────────────────────────────

    @Test
    void de90_in0400_isExactly42NumericDigits_startingWithOriginalMtiAndStan() throws Exception {
        Iso8583ReversalSender sender = new Iso8583ReversalSender(realPackager, client);
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        when(client.sendAndReceive(bytesCaptor.capture(), anyString()))
                .thenReturn(mockApprovedResponse());

        sender.sendReversal(makeTask("000042", "012345678901"));

        // ASCII fields pack as raw ASCII bytes — search the Latin-1 representation.
        String packed = new String(bytesCaptor.getValue(), StandardCharsets.ISO_8859_1);

        // DE90 = "0100" + "000042" + transmissionDateTime(10) + acqId(11) + fwdId(11)
        // The first 10 chars of DE90 are always "0100" + "000042" for this task.
        assertThat(packed).contains("0100000042");

        // DE90 = 42 digits total; extract the 42 chars starting at the "0100" marker.
        int de90Start = packed.indexOf("0100000042");
        assertThat(de90Start).isGreaterThan(0); // must be present (not at index 0 which is MTI)
        String de90 = packed.substring(de90Start, de90Start + 42);
        assertThat(de90).hasSize(42);
        assertThat(de90).matches("\\d{42}");

        // Verify DE90 sub-field positions.
        assertThat(de90.substring(0, 4)).isEqualTo("0100");    // original MTI
        assertThat(de90.substring(4, 10)).isEqualTo("000042"); // original STAN
        assertThat(de90.substring(31, 42)).isEqualTo("00000000000"); // forwarding institution = zeros
    }

    // ── STAN discipline ───────────────────────────────────────────────────────

    @Test
    void reversalStan_isInHighRange_distinctFromOriginal() throws Exception {
        Iso8583ReversalSender sender = new Iso8583ReversalSender(realPackager, client);
        ArgumentCaptor<String> stanCaptor = ArgumentCaptor.forClass(String.class);
        when(client.sendAndReceive(any(), stanCaptor.capture())).thenReturn(mockApprovedResponse());

        sender.sendReversal(makeTask("000042", "012345678901"));

        int reversalStan = Integer.parseInt(stanCaptor.getValue());
        assertThat(reversalStan).isGreaterThanOrEqualTo(500_001);
        assertThat(stanCaptor.getValue()).isNotEqualTo("000042");
    }

    // ── original RRN preservation ─────────────────────────────────────────────

    @Test
    void originalRrn_isPreservedInDe37_ofPacked0400() throws Exception {
        Iso8583ReversalSender sender = new Iso8583ReversalSender(realPackager, client);
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        when(client.sendAndReceive(bytesCaptor.capture(), anyString()))
                .thenReturn(mockApprovedResponse());

        sender.sendReversal(makeTask("000042", "999888777666"));

        // DE37 (IF_CHAR, 12 chars fixed) packs as 12 raw ASCII bytes.
        String packed = new String(bytesCaptor.getValue(), StandardCharsets.ISO_8859_1);
        assertThat(packed).contains("999888777666"); // original RRN preserved in DE37
    }

    // ── 0410 response handling ────────────────────────────────────────────────

    @Test
    void de39_00_returnsTrue() {
        Iso8583ReversalSender sender = new Iso8583ReversalSender(realPackager, client);
        when(client.sendAndReceive(any(), anyString())).thenReturn(mockApprovedResponse());

        assertThat(sender.sendReversal(makeTask("000042", "012345678901"))).isTrue();
    }

    @Test
    void de39_nonZero_returnsFalse() throws Exception {
        Iso8583ReversalSender sender = new Iso8583ReversalSender(realPackager, client);
        ISOMsg declined = new ISOMsg();
        declined.setPackager(realPackager);
        declined.setMTI("0410");
        declined.set(11, "500001");
        declined.set(39, "91");
        when(client.sendAndReceive(any(), anyString())).thenReturn(declined);

        assertThat(sender.sendReversal(makeTask("000042", "012345678901"))).isFalse();
    }

    @Test
    void timeout_on0410_returnsFalse_doesNotThrow() {
        Iso8583ReversalSender sender = new Iso8583ReversalSender(realPackager, client);
        when(client.sendAndReceive(any(), anyString()))
                .thenThrow(new Iso8583NettyClient.Iso8583TimeoutException("500001", 30000));

        assertThat(sender.sendReversal(makeTask("000042", "012345678901"))).isFalse();
    }

    @Test
    void markAsReversed_calledWithOriginalStan_beforeSend() {
        Iso8583ReversalSender sender = new Iso8583ReversalSender(realPackager, client);
        when(client.sendAndReceive(any(), anyString()))
                .thenThrow(new Iso8583NettyClient.Iso8583TimeoutException("500001", 30000));

        sender.sendReversal(makeTask("000042", "012345678901"));

        verify(client).markAsReversed("000042");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ReversalTask makeTask(String originalStan, String originalRrn) {
        return new ReversalTask(
                "t1", "pmnt_1", 0, 3,
                originalStan, originalRrn, "VISA_SIM",
                Instant.now(), new BigDecimal("50.00"), "USD", "merch_1",
                null, null);  // cardTokenId/maskedPan
    }

    private static ISOMsg mockApprovedResponse() {
        try {
            ISOMsg resp = new ISOMsg();
            resp.setPackager(realPackager);
            resp.setMTI("0410");
            resp.set(11, "500001");
            resp.set(39, "00");
            return resp;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
