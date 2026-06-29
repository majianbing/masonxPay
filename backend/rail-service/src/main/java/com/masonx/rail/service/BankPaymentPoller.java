package com.masonx.rail.service;

import com.masonx.rail.iso20022.BankRailHttpClient;
import com.masonx.rail.iso20022.Iso20022LogService;
import com.masonx.rail.iso20022.Iso20022ParsedMessage;
import com.masonx.rail.iso20022.Iso20022Parser;
import com.masonx.rail.service.RailPaymentRepository.PendingBankPayment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls the bank-sim for async ISO 20022 messages (pacs.002, pacs.004, camt.054)
 * for payments that are in ACCEPTED state.
 *
 * <p>Runs every 5 s. For each ACCEPTED bank payment:
 * <ol>
 *   <li>Calls {@code GET /bank-sim/payments/{endToEndId}/messages}.
 *   <li>For each message in the returned list, parses and applies the state transition.
 *   <li>pacs.002 ACSC → SETTLED; pacs.002 RJCT → DECLINED; pacs.004 → RETURNED; camt.054 → logged only.
 * </ol>
 *
 * <h3>MR4 — settlement events</h3>
 * On SETTLED publishes {@code BANK_CREDIT_TRANSFER}; on RETURNED publishes {@code BANK_RETURN}
 * so virtual-account-service can post the corresponding ledger journals.
 */
@Component
public class BankPaymentPoller {

    private static final Logger log = LoggerFactory.getLogger(BankPaymentPoller.class);

    private final RailPaymentRepository        paymentRepo;
    private final BankRailHttpClient           httpClient;
    private final Iso20022LogService           logService;
    private final RailSettlementEventPublisher publisher;
    private final String                       bankSimBaseUrl;

    public BankPaymentPoller(RailPaymentRepository paymentRepo,
                             BankRailHttpClient httpClient,
                             Iso20022LogService logService,
                             RailSettlementEventPublisher publisher,
                             @Value("${rail.simulator.bank-url:http://localhost:9090}") String bankSimBaseUrl) {
        this.paymentRepo    = paymentRepo;
        this.httpClient     = httpClient;
        this.logService     = logService;
        this.publisher      = publisher;
        this.bankSimBaseUrl = bankSimBaseUrl;
    }

    @Scheduled(fixedDelayString = "${rail.bank.poller.interval-ms:5000}")
    public void poll() {
        List<PendingBankPayment> pending = paymentRepo.findAcceptedBankPayments();
        if (pending.isEmpty()) return;

        log.debug("BankPaymentPoller: {} ACCEPTED payments to poll", pending.size());
        for (PendingBankPayment p : pending) {
            processPayment(p);
        }
    }

    private void processPayment(PendingBankPayment p) {
        List<String> messages = httpClient.fetchMessages(p.endToEndId(), bankSimBaseUrl);
        if (messages.isEmpty()) return;

        for (String xml : messages) {
            try {
                applyMessage(p, Iso20022Parser.parse(xml));
            } catch (Exception e) {
                log.error("Failed to process bank-sim message paymentId={} e2eId={}: {}",
                        p.paymentId(), p.endToEndId(), e.getMessage(), e);
            }
        }
    }

    private void applyMessage(PendingBankPayment p, Iso20022ParsedMessage msg) {
        logService.logReceive(p.paymentId(), p.network(), msg);

        switch (msg.type()) {
            case PACS_002 -> handlePaymentStatusReport(p, msg);
            case PACS_004 -> handlePaymentReturn(p, msg);
            case CAMT_054 -> log.info("camt.054 received paymentId={} amount={} currency={}",
                    p.paymentId(), msg.amount(), msg.currency());
            default -> log.warn("Unexpected message type {} paymentId={}", msg.type(), p.paymentId());
        }
    }

    /** Handles pacs.002 — Payment Status Report (settled or rejected). */
    private void handlePaymentStatusReport(PendingBankPayment p, Iso20022ParsedMessage msg) {
        if (msg.isSettled()) {
            paymentRepo.updateStatus(p.paymentId(), p.merchantId(), "SETTLED");
            log.info("Bank payment SETTLED paymentId={} e2eId={}", p.paymentId(), p.endToEndId());
            publisher.publishBankTransferSettled(
                    p.paymentId(), p.merchantId(), p.network(), msg.amount(), msg.currency());
        } else if (msg.isRejected()) {
            paymentRepo.updateStatus(p.paymentId(), p.merchantId(), "DECLINED");
            log.info("Bank payment DECLINED paymentId={} reason={}", p.paymentId(), msg.reasonCode());
        } else {
            log.info("pacs.002 intermediate status paymentId={} status={}", p.paymentId(), msg.statusCode());
        }
    }

    /** Handles pacs.004 — Payment Return (bank initiated return of funds). */
    private void handlePaymentReturn(PendingBankPayment p, Iso20022ParsedMessage msg) {
        paymentRepo.updateStatus(p.paymentId(), p.merchantId(), "RETURNED");
        log.info("Bank payment RETURNED paymentId={} e2eId={} reason={}",
                p.paymentId(), p.endToEndId(), msg.reasonCode());
        publisher.publishBankReturn(
                p.paymentId(), p.merchantId(), p.network(), msg.amount(), msg.currency());
    }
}
