package com.masonx.railsim.iso20022;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.xml.sax.InputSource;

/**
 * HTTP controller simulating the bank-rail network (SEPA / FedNow style).
 *
 * <p>Two endpoints:
 * <ul>
 *   <li>{@code POST /bank-sim/pain.001} — accepts a credit transfer initiation.
 *       Returns pain.002 immediately; queues subsequent messages for polling.
 *   <li>{@code GET /bank-sim/payments/{endToEndId}/messages} — returns and
 *       removes any queued messages (pacs.002, pacs.004, camt.054) for this payment.
 * </ul>
 */
@RestController
@RequestMapping("/bank-sim")
public class BankRailSimController {

    private static final Logger log = LoggerFactory.getLogger(BankRailSimController.class);

    private final BankRailSimService simService;

    public BankRailSimController(BankRailSimService simService) {
        this.simService = simService;
    }

    @PostMapping(value = "/pain.001",
            consumes = MediaType.APPLICATION_XML_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> receivePain001(@RequestBody String xmlBody) {
        try {
            DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            org.w3c.dom.Document doc = db.parse(new InputSource(new StringReader(xmlBody)));
            XPath xpath = XPathFactory.newInstance().newXPath();

            String originalMsgId  = xpath.evaluate("//*[local-name()='MsgId'][1]", doc);
            String endToEndId     = xpath.evaluate("//*[local-name()='EndToEndId']", doc);
            String creditorAcct   = xpath.evaluate("//*[local-name()='CdtrAcct']//*[local-name()='Id'][last()]", doc);
            String debtorAcct     = xpath.evaluate("//*[local-name()='DbtrAcct']//*[local-name()='Id'][last()]", doc);
            String amountStr      = xpath.evaluate("//*[local-name()='InstdAmt']", doc);
            String currency       = xpath.evaluate("//*[local-name()='InstdAmt']/@Ccy", doc);

            BigDecimal amount = parseBigDecimal(amountStr);
            String transactionId = "TXN_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            String pain002 = simService.acceptPain001(
                    originalMsgId, endToEndId, transactionId,
                    creditorAcct, amount, currency, debtorAcct);

            return ResponseEntity.ok(pain002);

        } catch (Exception e) {
            log.error("Bank-sim failed to parse pain.001: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(errorXml(e.getMessage()));
        }
    }

    @GetMapping(value = "/payments/{endToEndId}/messages",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<String>> getPendingMessages(@PathVariable String endToEndId) {
        List<String> messages = simService.drainMessages(endToEndId);
        return ResponseEntity.ok(messages);
    }

    private static BigDecimal parseBigDecimal(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private static String errorXml(String message) {
        return """
               <?xml version="1.0"?>
               <Error><Message>%s</Message></Error>
               """.formatted(message == null ? "unknown" : message.replace("<", "&lt;"));
    }
}
