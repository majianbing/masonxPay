package com.masonx.railsim.iso20022;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Builds simplified ISO 20022 response XML for the bank-rail simulator.
 *
 * <p>Messages use correct element names and namespace URIs but omit optional
 * fields that are not needed for the lab. This is sufficient to demonstrate
 * the pain.001 → pain.002 → pacs.002 → camt.054 message chain.
 */
class SimXmlFactory {

    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** pain.002 — customer payment status report (ACCP or RJCT). */
    static String pain002(String originalMsgId, String endToEndId, String txStatus, String reasonCode) {
        String rsn = reasonCode != null
                ? "<StsRsnInf><Rsn><Cd>" + reasonCode + "</Cd></Rsn></StsRsnInf>"
                : "";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.002.001.11">
                  <CstmrPmtStsRpt>
                    <GrpHdr>
                      <MsgId>SIM_PAIN002_%s</MsgId>
                      <CreDtTm>%s</CreDtTm>
                    </GrpHdr>
                    <OrgnlGrpInfAndSts>
                      <OrgnlMsgId>%s</OrgnlMsgId>
                    </OrgnlGrpInfAndSts>
                    <OrgnlPmtInfAndSts>
                      <TxInfAndSts>
                        <OrgnlEndToEndId>%s</OrgnlEndToEndId>
                        <TxSts>%s</TxSts>
                        %s
                      </TxInfAndSts>
                    </OrgnlPmtInfAndSts>
                  </CstmrPmtStsRpt>
                </Document>
                """.formatted(
                System.nanoTime(), now(), originalMsgId, endToEndId, txStatus, rsn);
    }

    /** pacs.002 — FI-to-FI payment status report (ACSC settlement or RJCT). */
    static String pacs002(String endToEndId, String transactionId, String txStatus) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.11">
                  <FIToFIPmtStsRpt>
                    <GrpHdr>
                      <MsgId>SIM_PACS002_%s</MsgId>
                      <CreDtTm>%s</CreDtTm>
                    </GrpHdr>
                    <TxInfAndSts>
                      <OrgnlEndToEndId>%s</OrgnlEndToEndId>
                      <TxId>%s</TxId>
                      <TxSts>%s</TxSts>
                    </TxInfAndSts>
                  </FIToFIPmtStsRpt>
                </Document>
                """.formatted(System.nanoTime(), now(), endToEndId, transactionId, txStatus);
    }

    /** pacs.004 — payment return. New credit transfer in the opposite direction. */
    static String pacs004(String endToEndId, String originalTransactionId,
                          String returnId, BigDecimal amount, String currency) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.004.001.10">
                  <PmtRtr>
                    <GrpHdr>
                      <MsgId>SIM_PACS004_%s</MsgId>
                      <CreDtTm>%s</CreDtTm>
                    </GrpHdr>
                    <TxInf>
                      <RtrId>%s</RtrId>
                      <OrgnlEndToEndId>%s</OrgnlEndToEndId>
                      <OrgnlTxId>%s</OrgnlTxId>
                      <RtrRsnInf><Rsn><Cd>AC03</Cd></Rsn></RtrRsnInf>
                      <RtrdInstdAmt Ccy="%s">%s</RtrdInstdAmt>
                    </TxInf>
                  </PmtRtr>
                </Document>
                """.formatted(
                System.nanoTime(), now(), returnId, endToEndId, originalTransactionId,
                currency, amount.toPlainString());
    }

    /** camt.054 — bank-to-customer debit/credit notification. */
    static String camt054(String endToEndId, String debtorAccount,
                          BigDecimal amount, String currency) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.054.001.08">
                  <BkToCstmrDbtCdtNtfctn>
                    <GrpHdr>
                      <MsgId>SIM_CAMT054_%s</MsgId>
                      <CreDtTm>%s</CreDtTm>
                    </GrpHdr>
                    <Ntfctn>
                      <Id>NTFCTN_%s</Id>
                      <Acct><Id><Othr><Id>%s</Id></Othr></Id></Acct>
                      <Ntry>
                        <Amt Ccy="%s">%s</Amt>
                        <CdtDbtInd>DBIT</CdtDbtInd>
                        <Sts><Cd>BOOK</Cd></Sts>
                        <NtryDtls><TxDtls>
                          <Refs><EndToEndId>%s</EndToEndId></Refs>
                        </TxDtls></NtryDtls>
                      </Ntry>
                    </Ntfctn>
                  </BkToCstmrDbtCdtNtfctn>
                </Document>
                """.formatted(
                System.nanoTime(), now(), System.nanoTime(), debtorAccount,
                currency, amount.toPlainString(), endToEndId);
    }

    /** camt.054 with an intentionally wrong amount — used for reconciliation exception testing. */
    static String camt054WrongAmount(String endToEndId, String debtorAccount,
                                     BigDecimal amount, String currency) {
        BigDecimal wrong = amount.add(BigDecimal.ONE);
        return camt054(endToEndId, debtorAccount, wrong, currency);
    }

    private static String now() {
        return DT_FMT.format(Instant.now());
    }
}
