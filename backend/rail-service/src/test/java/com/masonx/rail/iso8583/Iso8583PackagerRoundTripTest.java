package com.masonx.rail.iso8583;

import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that all DE fields used in rail-service pack and unpack correctly with
 * the GenericPackager configuration. This is a pure jPOS unit test — no Spring context.
 */
class Iso8583PackagerRoundTripTest {

    private static GenericPackager packager;

    @BeforeAll
    static void loadPackager() throws Exception {
        packager = new GenericPackager(
                Iso8583PackagerRoundTripTest.class.getClassLoader()
                        .getResourceAsStream("iso8583-packager.xml"));
    }

    @Test
    void authRequest_roundTrip_allFieldsPreserved() throws Exception {
        ISOMsg msg = new ISOMsg();
        msg.setPackager(packager);
        msg.setMTI("0100");
        msg.set(2,  "4111111111110000"); // PAN
        msg.set(3,  "000000");
        msg.set(4,  "000000005000");     // $50.00
        msg.set(7,  "0101120000");       // MMDDHHmmss
        msg.set(11, "000001");
        msg.set(12, "120000");
        msg.set(13, "0101");
        msg.set(32, "999001");
        msg.set(37, "000000000001");
        msg.set(41, "TERM0001");
        msg.set(42, "MERCHANT000001 ");
        msg.set(49, "840");

        byte[] packed = msg.pack();
        assertThat(packed).isNotEmpty();

        ISOMsg unpacked = new ISOMsg();
        unpacked.setPackager(packager);
        unpacked.unpack(packed);

        assertThat(unpacked.getMTI()).isEqualTo("0100");
        assertThat(unpacked.getString(2)).isEqualTo("4111111111110000");
        assertThat(unpacked.getString(3)).isEqualTo("000000");
        assertThat(unpacked.getString(4)).isEqualTo("000000005000");
        assertThat(unpacked.getString(7)).isEqualTo("0101120000");
        assertThat(unpacked.getString(11)).isEqualTo("000001");
        assertThat(unpacked.getString(12)).isEqualTo("120000");
        assertThat(unpacked.getString(13)).isEqualTo("0101");
        assertThat(unpacked.getString(32)).isEqualTo("999001");
        assertThat(unpacked.getString(37)).isEqualTo("000000000001");
        assertThat(unpacked.getString(41)).isEqualTo("TERM0001");
        assertThat(unpacked.getString(49)).isEqualTo("840");
    }

    @Test
    void authResponse_roundTrip_withAuthCode() throws Exception {
        ISOMsg msg = new ISOMsg();
        msg.setPackager(packager);
        msg.setMTI("0110");
        msg.set(11, "000001");
        msg.set(37, "000000000001");
        msg.set(38, "AUTH01");   // auth code
        msg.set(39, "00");       // approved
        msg.set(49, "840");

        byte[] packed = msg.pack();

        ISOMsg unpacked = new ISOMsg();
        unpacked.setPackager(packager);
        unpacked.unpack(packed);

        assertThat(unpacked.getMTI()).isEqualTo("0110");
        assertThat(unpacked.getString(11)).isEqualTo("000001");
        assertThat(unpacked.getString(38).trim()).isEqualTo("AUTH01");
        assertThat(unpacked.getString(39)).isEqualTo("00");
    }

    @Test
    void declineResponse_roundTrip() throws Exception {
        ISOMsg msg = new ISOMsg();
        msg.setPackager(packager);
        msg.setMTI("0110");
        msg.set(11, "000002");
        msg.set(39, "51"); // insufficient funds

        byte[] packed = msg.pack();

        ISOMsg unpacked = new ISOMsg();
        unpacked.setPackager(packager);
        unpacked.unpack(packed);

        assertThat(unpacked.getString(11)).isEqualTo("000002");
        assertThat(unpacked.getString(39)).isEqualTo("51");
        assertThat(unpacked.hasField(38)).isFalse();
    }

    @Test
    void stanCorrelation_roundTrip_stanPreserved() throws Exception {
        // STAN must survive the pack/unpack cycle unchanged — it's the correlation key.
        String stan = "987654";
        ISOMsg request = new ISOMsg();
        request.setPackager(packager);
        request.setMTI("0100");
        request.set(11, stan);
        request.set(39, "00");

        byte[] packed = request.pack();

        ISOMsg response = new ISOMsg();
        response.setPackager(packager);
        response.unpack(packed);

        assertThat(response.getString(11)).isEqualTo(stan);
    }
}
