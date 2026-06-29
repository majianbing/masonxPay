package com.masonx.rail.iso20022;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import org.xml.sax.InputSource;
import org.w3c.dom.Document;

/**
 * Parses ISO 20022 response XML messages using DOM/XPath.
 *
 * <p>Uses {@code local-name()} XPath predicates to avoid namespace-binding
 * complexity while still reading correctly from namespace-qualified documents.
 * All parsing is defensive — missing fields yield null rather than exceptions.
 */
public final class Iso20022Parser {

    private static final Logger log = LoggerFactory.getLogger(Iso20022Parser.class);

    private Iso20022Parser() {}

    /**
     * Parses an ISO 20022 XML message and returns a canonical parsed form.
     *
     * @throws IllegalArgumentException if the XML cannot be parsed or the message
     *                                  type cannot be determined from the root element
     */
    public static Iso20022ParsedMessage parse(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(xml)));
            XPath xpath = XPathFactory.newInstance().newXPath();

            Iso20022MessageType type = detectType(doc, xpath);
            return switch (type) {
                case PAIN_002 -> parsePain002(doc, xpath, type);
                case PACS_002 -> parsePacs002(doc, xpath, type);
                case PACS_004 -> parsePacs004(doc, xpath, type);
                case CAMT_054 -> parseCamt054(doc, xpath, type);
                default       -> throw new IllegalArgumentException("Unsupported parse type: " + type);
            };
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse ISO 20022 XML: " + e.getMessage(), e);
        }
    }

    private static Iso20022MessageType detectType(Document doc, XPath xpath) throws Exception {
        // Second-level container element name is the most reliable type discriminator.
        // Using local-name() function — works with or without namespace binding.
        String container = xpath.evaluate("local-name(/*/*[1])", doc);
        if ("CstmrPmtStsRpt".equals(container))      return Iso20022MessageType.PAIN_002;
        if ("FIToFIPmtStsRpt".equals(container))     return Iso20022MessageType.PACS_002;
        if ("PmtRtr".equals(container))               return Iso20022MessageType.PACS_004;
        if ("BkToCstmrDbtCdtNtfctn".equals(container)) return Iso20022MessageType.CAMT_054;

        // Fallback: namespace URI on the root Document element
        String ns = doc.getDocumentElement().getNamespaceURI();
        if (ns != null) {
            if (ns.contains("pain.002")) return Iso20022MessageType.PAIN_002;
            if (ns.contains("pacs.002")) return Iso20022MessageType.PACS_002;
            if (ns.contains("pacs.004")) return Iso20022MessageType.PACS_004;
            if (ns.contains("camt.054")) return Iso20022MessageType.CAMT_054;
        }

        String root = doc.getDocumentElement().getLocalName();
        if (root == null) root = doc.getDocumentElement().getNodeName();
        throw new IllegalArgumentException("Cannot detect ISO 20022 message type from root='" + root + "'");
    }

    private static Iso20022ParsedMessage parsePain002(Document doc, XPath xpath,
                                                       Iso20022MessageType type) throws Exception {
        String messageId  = xeval(xpath, doc, "//*[local-name()='MsgId'][1]");
        String e2eId      = xeval(xpath, doc, "//*[local-name()='OrgnlEndToEndId']");
        String txStatus   = xeval(xpath, doc, "//*[local-name()='TxSts']");
        String reasonCode = xeval(xpath, doc, "//*[local-name()='StsRsnInf']//*[local-name()='Cd']");
        return new Iso20022ParsedMessage(type, messageId, e2eId, null, null,
                txStatus, reasonCode, null, null);
    }

    private static Iso20022ParsedMessage parsePacs002(Document doc, XPath xpath,
                                                       Iso20022MessageType type) throws Exception {
        String messageId  = xeval(xpath, doc, "//*[local-name()='MsgId'][1]");
        String e2eId      = xeval(xpath, doc, "//*[local-name()='OrgnlEndToEndId']");
        String txId       = xeval(xpath, doc, "//*[local-name()='TxId']");
        String txStatus   = xeval(xpath, doc, "//*[local-name()='TxSts']");
        return new Iso20022ParsedMessage(type, messageId, e2eId, null, txId,
                txStatus, null, null, null);
    }

    private static Iso20022ParsedMessage parsePacs004(Document doc, XPath xpath,
                                                       Iso20022MessageType type) throws Exception {
        String messageId  = xeval(xpath, doc, "//*[local-name()='MsgId'][1]");
        String e2eId      = xeval(xpath, doc, "//*[local-name()='OrgnlEndToEndId']");
        String rtrId      = xeval(xpath, doc, "//*[local-name()='RtrId']");
        String reasonCode = xeval(xpath, doc, "//*[local-name()='RtrRsnInf']//*[local-name()='Cd']");
        String amtStr     = xeval(xpath, doc, "//*[local-name()='RtrdInstdAmt']");
        String currency   = xeval(xpath, doc, "//*[local-name()='RtrdInstdAmt']/@Ccy");
        BigDecimal amount = parseBigDecimal(amtStr);
        return new Iso20022ParsedMessage(type, messageId, e2eId, null, rtrId,
                null, reasonCode, amount, currency);
    }

    private static Iso20022ParsedMessage parseCamt054(Document doc, XPath xpath,
                                                       Iso20022MessageType type) throws Exception {
        String messageId = xeval(xpath, doc, "//*[local-name()='MsgId'][1]");
        String e2eId     = xeval(xpath, doc, "//*[local-name()='EndToEndId']");
        String amtStr    = xeval(xpath, doc, "//*[local-name()='Ntry']//*[local-name()='Amt']");
        String currency  = xeval(xpath, doc, "//*[local-name()='Ntry']//*[local-name()='Amt']/@Ccy");
        BigDecimal amount = parseBigDecimal(amtStr);
        return new Iso20022ParsedMessage(type, messageId, e2eId, null, null,
                null, null, amount, currency);
    }

    private static String xeval(XPath xpath, Document doc, String expr) {
        try {
            String result = xpath.evaluate(expr, doc);
            return (result == null || result.isBlank()) ? null : result.trim();
        } catch (Exception e) {
            log.debug("XPath eval failed for '{}': {}", expr, e.getMessage());
            return null;
        }
    }

    private static BigDecimal parseBigDecimal(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
