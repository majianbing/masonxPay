package com.masonx.railsim.iso8583;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rule-based issuer for non-VA test PANs.
 *
 * <p>Decision is driven by the last 4 digits of the PAN (the "suffix"):
 * <ul>
 *   <li>0000 → Approved (DE39 = 00)
 *   <li>0001 → Declined — insufficient funds (DE39 = 51)
 *   <li>0002 → Declined — do not honor (DE39 = 05)
 *   <li>0003 → No response (timeout; caller must not send a response)
 *   <li>0004 → Late response — caller sleeps past the client timeout then responds
 *   <li>0005 → Duplicate response — caller responds twice
 *   <li>0006 → Declined — invalid card (DE39 = 14)
 *   <li>0007 → Declined — issuer unavailable (DE39 = 91)
 *   <li>other → Approved
 * </ul>
 *
 * <p>Suffixes 0003, 0004, 0005 affect timing in {@link CardNetworkSimHandler}.
 * This class only returns the decision for suffixes that produce a normal response.
 */
@Component
public class IssuerSimulator {

    public enum Scenario {
        APPROVE,
        DECLINE_INSUFFICIENT_FUNDS,
        DECLINE_DO_NOT_HONOR,
        TIMEOUT,
        LATE_RESPONSE,
        DUPLICATE_RESPONSE,
        DECLINE_INVALID_CARD,
        DECLINE_ISSUER_UNAVAILABLE
    }

    public Scenario detectScenario(String pan) {
        if (pan == null || pan.length() < 4) return Scenario.APPROVE;
        String suffix = pan.substring(pan.length() - 4);
        return switch (suffix) {
            case "0001" -> Scenario.DECLINE_INSUFFICIENT_FUNDS;
            case "0002" -> Scenario.DECLINE_DO_NOT_HONOR;
            case "0003" -> Scenario.TIMEOUT;
            case "0004" -> Scenario.LATE_RESPONSE;
            case "0005" -> Scenario.DUPLICATE_RESPONSE;
            case "0006" -> Scenario.DECLINE_INVALID_CARD;
            case "0007" -> Scenario.DECLINE_ISSUER_UNAVAILABLE;
            default     -> Scenario.APPROVE;
        };
    }

    /** Returns the DE39 response code for scenarios that produce a synchronous response. */
    public String responseCode(Scenario scenario) {
        return switch (scenario) {
            case APPROVE                   -> "00";
            case DECLINE_INSUFFICIENT_FUNDS-> "51";
            case DECLINE_DO_NOT_HONOR      -> "05";
            case DECLINE_INVALID_CARD      -> "14";
            case DECLINE_ISSUER_UNAVAILABLE-> "91";
            // Timing scenarios are handled by the handler; return 00 for the eventual response.
            case LATE_RESPONSE, DUPLICATE_RESPONSE -> "00";
            case TIMEOUT                   -> null; // no response sent
        };
    }

    public String generateAuthCode() {
        return String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
