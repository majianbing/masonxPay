package com.masonx.rail.iso20022;

import com.masonx.rail.canonical.CanonicalPaymentCommand;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Builds an ISO 20022 pain.001 (CustomerCreditTransferInitiation) XML document.
 *
 * <p>Uses the simplified message structure required by the lab:
 * correct ISO 20022 element names and namespace, key fields only,
 * no full XSD validation.
 */
public final class Pain001Builder {

    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private Pain001Builder() {}

    /**
     * Builds a pain.001 XML string.
     *
     * @param command        canonical payment command with amount, currency, accounts
     * @param messageId      unique document identifier
     * @param instructionId  per-instruction identifier
     * @param endToEndId     stable ID that must be echoed in all subsequent messages
     */
    public static String build(CanonicalPaymentCommand command,
                               String messageId,
                               String instructionId,
                               String endToEndId) {
        String debtorIban   = command.debtorAccount()   != null ? command.debtorAccount().iban()   : "UNKNOWN";
        String creditorIban = command.creditorAccount() != null ? command.creditorAccount().iban()  : "UNKNOWN";
        String debtorName   = command.debtorAccount()   != null ? command.debtorAccount().name()   : "MasonXPay Merchant";
        String creditorName = command.creditorAccount() != null ? command.creditorAccount().name()  : "Counterparty";
        String amount       = command.amount().toPlainString();
        String currency     = command.currency();
        String createdAt    = DT_FMT.format(Instant.now());

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09">
                  <CstmrCdtTrfInitn>
                    <GrpHdr>
                      <MsgId>%s</MsgId>
                      <CreDtTm>%s</CreDtTm>
                      <NbOfTxs>1</NbOfTxs>
                      <CtrlSum>%s</CtrlSum>
                      <InitgPty><Nm>MasonXPay Rail Service</Nm></InitgPty>
                    </GrpHdr>
                    <PmtInf>
                      <PmtInfId>%s_INF</PmtInfId>
                      <PmtMtd>TRF</PmtMtd>
                      <NbOfTxs>1</NbOfTxs>
                      <CtrlSum>%s</CtrlSum>
                      <CdtTrfTxInf>
                        <PmtId>
                          <InstrId>%s</InstrId>
                          <EndToEndId>%s</EndToEndId>
                        </PmtId>
                        <Amt>
                          <InstdAmt Ccy="%s">%s</InstdAmt>
                        </Amt>
                        <Dbtr><Nm>%s</Nm></Dbtr>
                        <DbtrAcct><Id><Othr><Id>%s</Id></Othr></Id></DbtrAcct>
                        <Cdtr><Nm>%s</Nm></Cdtr>
                        <CdtrAcct><Id><Othr><Id>%s</Id></Othr></Id></CdtrAcct>
                        <RmtInf><Ustrd>%s</Ustrd></RmtInf>
                      </CdtTrfTxInf>
                    </PmtInf>
                  </CstmrCdtTrfInitn>
                </Document>
                """.formatted(
                messageId, createdAt, amount,
                messageId, amount,
                instructionId, endToEndId,
                currency, amount,
                escape(debtorName), debtorIban,
                escape(creditorName), creditorIban,
                command.paymentId()
        );
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
