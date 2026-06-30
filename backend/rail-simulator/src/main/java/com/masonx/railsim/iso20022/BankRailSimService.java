package com.masonx.railsim.iso20022;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateful bank-rail simulator service.
 *
 * <p>On pain.001 receipt the scenario is determined from the creditor account suffix
 * and a queue of pending response messages is built. The poller in rail-service
 * calls {@link #drainMessages(String)} to consume them.
 *
 * <p>All state is in-memory — sufficient for the lab. Restart clears all state.
 */
@Service
public class BankRailSimService {

    private static final Logger log = LoggerFactory.getLogger(BankRailSimService.class);

    // endToEndId → pending messages (consumed on drain)
    private final ConcurrentHashMap<String, List<String>> pendingMessages = new ConcurrentHashMap<>();
    // endToEndId → poll count (for delayed-settle scenario)
    private final ConcurrentHashMap<String, Integer> pollCount = new ConcurrentHashMap<>();

    /**
     * Processes a received pain.001. Returns the immediate pain.002 response.
     *
     * <p>The scenario determines what messages will be queued for later polling.
     */
    public String acceptPain001(String originalMsgId, String endToEndId,
                                String transactionId, String creditorAccount,
                                BigDecimal amount, String currency, String debtorAccount) {
        BankScenario scenario = BankScenario.fromAccountSuffix(creditorAccount);
        log.info("Bank-sim received pain.001 endToEndId={} creditorAccount={} scenario={}",
                endToEndId, creditorAccount, scenario);

        String pain002;

        switch (scenario) {
            case REJECT -> {
                pain002 = SimXmlFactory.pain002(originalMsgId, endToEndId, "RJCT", "AC01");
                // No further messages queued.
            }
            case SETTLE -> {
                pain002 = SimXmlFactory.pain002(originalMsgId, endToEndId, "ACCP", null);
                queueMessages(endToEndId,
                        SimXmlFactory.pacs002(endToEndId, transactionId, "ACSC"),
                        SimXmlFactory.camt054(endToEndId, debtorAccount, amount, currency));
            }
            case RETURN -> {
                String returnId = "RTN_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                pain002 = SimXmlFactory.pain002(originalMsgId, endToEndId, "ACCP", null);
                queueMessages(endToEndId,
                        SimXmlFactory.pacs002(endToEndId, transactionId, "ACSP"),
                        SimXmlFactory.pacs004(endToEndId, transactionId, returnId, amount, currency));
            }
            case PENDING -> {
                pain002 = SimXmlFactory.pain002(originalMsgId, endToEndId, "ACCP", null);
                // Nothing queued — payment stays ACCEPTED indefinitely.
            }
            case DUPLICATE_STATUS -> {
                pain002 = SimXmlFactory.pain002(originalMsgId, endToEndId, "ACCP", null);
                String pacs002Xml = SimXmlFactory.pacs002(endToEndId, transactionId, "ACSC");
                queueMessages(endToEndId, pacs002Xml, pacs002Xml); // duplicate intentional
            }
            case DELAYED_SETTLE -> {
                // pacs.002 only queued after second poll — tested by pollCount
                pain002 = SimXmlFactory.pain002(originalMsgId, endToEndId, "ACCP", null);
                pendingMessages.put(endToEndId, new ArrayList<>()); // queue exists but empty
                pollCount.put(endToEndId, 0);
                // Store delayed messages for delivery on second poll
                pendingMessages.computeIfPresent(endToEndId, (k, v) -> v); // mark for delayed path
                // Mark delayed scenario
                pendingMessages.put("delayed_" + endToEndId, List.of(
                        SimXmlFactory.pacs002(endToEndId, transactionId, "ACSC"),
                        SimXmlFactory.camt054(endToEndId, debtorAccount, amount, currency)));
            }
            case AMOUNT_MISMATCH -> {
                pain002 = SimXmlFactory.pain002(originalMsgId, endToEndId, "ACCP", null);
                queueMessages(endToEndId,
                        SimXmlFactory.pacs002(endToEndId, transactionId, "ACSC"),
                        SimXmlFactory.camt054WrongAmount(endToEndId, debtorAccount, amount, currency));
            }
            default -> {
                pain002 = SimXmlFactory.pain002(originalMsgId, endToEndId, "ACCP", null);
            }
        }

        return pain002;
    }

    /**
     * Returns and removes all pending response messages for the given EndToEndId.
     *
     * <p>Rail-service calls this on each polling cycle. Messages are returned in
     * arrival order (pain.002 is handled by the pain.001 response; this returns
     * subsequent messages: pacs.002, pacs.004, camt.054).
     */
    public List<String> drainMessages(String endToEndId) {
        // DELAYED_SETTLE: deliver messages on second poll
        if (pendingMessages.containsKey("delayed_" + endToEndId)) {
            int count = pollCount.merge(endToEndId, 1, Integer::sum);
            if (count >= 2) {
                List<String> delayed = pendingMessages.remove("delayed_" + endToEndId);
                pendingMessages.remove(endToEndId);
                pollCount.remove(endToEndId);
                log.info("Bank-sim delivering delayed messages for endToEndId={} on poll #{}", endToEndId, count);
                return delayed != null ? delayed : List.of();
            }
            log.info("Bank-sim holding delayed messages for endToEndId={} on poll #{}", endToEndId, count);
            return List.of();
        }

        List<String> msgs = pendingMessages.remove(endToEndId);
        if (msgs != null && !msgs.isEmpty()) {
            log.info("Bank-sim delivering {} message(s) for endToEndId={}", msgs.size(), endToEndId);
        }
        return msgs != null ? msgs : List.of();
    }

    private void queueMessages(String endToEndId, String... xmlMessages) {
        pendingMessages.put(endToEndId, new ArrayList<>(List.of(xmlMessages)));
    }
}
