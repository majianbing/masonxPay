package com.masonx.rail.iso20022;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link Iso20022Parser} — message type detection and field extraction.
 */
class Iso20022ParserTest {

    @Test
    void parse_pain002_accp_extractsFields() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.002.001.11">
                  <CstmrPmtStsRpt>
                    <GrpHdr><MsgId>MSG_001</MsgId><CreDtTm>2026-06-29T10:00:00</CreDtTm></GrpHdr>
                    <OrgnlGrpInfAndSts><OrgnlMsgId>ORIG_001</OrgnlMsgId></OrgnlGrpInfAndSts>
                    <OrgnlPmtInfAndSts>
                      <TxInfAndSts>
                        <OrgnlEndToEndId>E2E_001</OrgnlEndToEndId>
                        <TxSts>ACCP</TxSts>
                      </TxInfAndSts>
                    </OrgnlPmtInfAndSts>
                  </CstmrPmtStsRpt>
                </Document>
                """;
        Iso20022ParsedMessage msg = Iso20022Parser.parse(xml);

        assertThat(msg.type()).isEqualTo(Iso20022MessageType.PAIN_002);
        assertThat(msg.messageId()).isEqualTo("MSG_001");
        assertThat(msg.endToEndId()).isEqualTo("E2E_001");
        assertThat(msg.statusCode()).isEqualTo("ACCP");
        assertThat(msg.isAccepted()).isTrue();
        assertThat(msg.isRejected()).isFalse();
    }

    @Test
    void parse_pain002_rjct_extractsReasonCode() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.002.001.11">
                  <CstmrPmtStsRpt>
                    <GrpHdr><MsgId>MSG_002</MsgId><CreDtTm>2026-06-29T10:00:00</CreDtTm></GrpHdr>
                    <OrgnlGrpInfAndSts><OrgnlMsgId>ORIG_002</OrgnlMsgId></OrgnlGrpInfAndSts>
                    <OrgnlPmtInfAndSts>
                      <TxInfAndSts>
                        <OrgnlEndToEndId>E2E_002</OrgnlEndToEndId>
                        <TxSts>RJCT</TxSts>
                        <StsRsnInf><Rsn><Cd>AC01</Cd></Rsn></StsRsnInf>
                      </TxInfAndSts>
                    </OrgnlPmtInfAndSts>
                  </CstmrPmtStsRpt>
                </Document>
                """;
        Iso20022ParsedMessage msg = Iso20022Parser.parse(xml);

        assertThat(msg.type()).isEqualTo(Iso20022MessageType.PAIN_002);
        assertThat(msg.statusCode()).isEqualTo("RJCT");
        assertThat(msg.reasonCode()).isEqualTo("AC01");
        assertThat(msg.isRejected()).isTrue();
    }

    @Test
    void parse_pacs002_acsc_extractsSettlementFields() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.11">
                  <FIToFIPmtStsRpt>
                    <GrpHdr><MsgId>PACS_001</MsgId><CreDtTm>2026-06-29T11:00:00</CreDtTm></GrpHdr>
                    <TxInfAndSts>
                      <OrgnlEndToEndId>E2E_003</OrgnlEndToEndId>
                      <TxId>TXN_001</TxId>
                      <TxSts>ACSC</TxSts>
                    </TxInfAndSts>
                  </FIToFIPmtStsRpt>
                </Document>
                """;
        Iso20022ParsedMessage msg = Iso20022Parser.parse(xml);

        assertThat(msg.type()).isEqualTo(Iso20022MessageType.PACS_002);
        assertThat(msg.endToEndId()).isEqualTo("E2E_003");
        assertThat(msg.transactionId()).isEqualTo("TXN_001");
        assertThat(msg.statusCode()).isEqualTo("ACSC");
        assertThat(msg.isSettled()).isTrue();
    }

    @Test
    void parse_pacs004_extractsReturnAmount() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.004.001.10">
                  <PmtRtr>
                    <GrpHdr><MsgId>RTN_001</MsgId><CreDtTm>2026-06-29T12:00:00</CreDtTm></GrpHdr>
                    <TxInf>
                      <RtrId>RTNID_001</RtrId>
                      <OrgnlEndToEndId>E2E_004</OrgnlEndToEndId>
                      <OrgnlTxId>TXN_002</OrgnlTxId>
                      <RtrRsnInf><Rsn><Cd>AC03</Cd></Rsn></RtrRsnInf>
                      <RtrdInstdAmt Ccy="EUR">100.00</RtrdInstdAmt>
                    </TxInf>
                  </PmtRtr>
                </Document>
                """;
        Iso20022ParsedMessage msg = Iso20022Parser.parse(xml);

        assertThat(msg.type()).isEqualTo(Iso20022MessageType.PACS_004);
        assertThat(msg.endToEndId()).isEqualTo("E2E_004");
        assertThat(msg.reasonCode()).isEqualTo("AC03");
        assertThat(msg.amount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(msg.currency()).isEqualTo("EUR");
    }

    @Test
    void parse_camt054_extractsAmount() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.054.001.08">
                  <BkToCstmrDbtCdtNtfctn>
                    <GrpHdr><MsgId>CAMT_001</MsgId><CreDtTm>2026-06-29T13:00:00</CreDtTm></GrpHdr>
                    <Ntfctn>
                      <Id>NTFCTN_001</Id>
                      <Acct><Id><Othr><Id>DE89370400440532013001</Id></Othr></Id></Acct>
                      <Ntry>
                        <Amt Ccy="EUR">100.00</Amt>
                        <CdtDbtInd>DBIT</CdtDbtInd>
                        <Sts><Cd>BOOK</Cd></Sts>
                        <NtryDtls><TxDtls>
                          <Refs><EndToEndId>E2E_005</EndToEndId></Refs>
                        </TxDtls></NtryDtls>
                      </Ntry>
                    </Ntfctn>
                  </BkToCstmrDbtCdtNtfctn>
                </Document>
                """;
        Iso20022ParsedMessage msg = Iso20022Parser.parse(xml);

        assertThat(msg.type()).isEqualTo(Iso20022MessageType.CAMT_054);
        assertThat(msg.messageId()).isEqualTo("CAMT_001");
        assertThat(msg.endToEndId()).isEqualTo("E2E_005");
        assertThat(msg.amount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(msg.currency()).isEqualTo("EUR");
    }

    @Test
    void parse_unknownXml_throwsIllegalArgument() {
        assertThatThrownBy(() -> Iso20022Parser.parse("<Document><Unknown/></Document>"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_malformedXml_throwsIllegalArgument() {
        assertThatThrownBy(() -> Iso20022Parser.parse("this is not xml"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
