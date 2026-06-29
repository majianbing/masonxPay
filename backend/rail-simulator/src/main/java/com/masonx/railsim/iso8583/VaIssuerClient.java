package com.masonx.railsim.iso8583;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

/**
 * HTTP client that calls virtual-account-service's issuer auth endpoint for BIN 999999 cards.
 *
 * <p>BIN 999999 identifies VA-issued VCCs. The card network sim acts as the card network
 * but delegates authorization decisions to the VA service (the card issuer).
 */
@Component
public class VaIssuerClient {

    private static final Logger log = LoggerFactory.getLogger(VaIssuerClient.class);

    private final String vaIssuerUrl;
    private final RestTemplate restTemplate;

    public VaIssuerClient(
            @Value("${railsim.va-issuer-url:http://localhost:8086/internal/issuer/authorize}")
            String vaIssuerUrl) {
        this.vaIssuerUrl  = vaIssuerUrl;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Calls the VA issuer endpoint and returns its decision.
     * On any HTTP or network error, returns a decline with DE39=91 (issuer unavailable).
     */
    public SimIssuerAuthResponse authorize(String maskedPan, BigDecimal amount,
                                           String currency, String stan, String rrn) {
        var request = new SimIssuerAuthRequest(maskedPan, amount, currency, stan, rrn);
        try {
            SimIssuerAuthResponse resp = restTemplate.postForObject(
                    vaIssuerUrl, request, SimIssuerAuthResponse.class);
            if (resp == null) {
                log.error("VA issuer returned null response for maskedPan={}", maskedPan);
                return new SimIssuerAuthResponse("DECLINED", "91", null, "Null response from issuer");
            }
            return resp;
        } catch (RestClientException e) {
            log.error("VA issuer call failed for maskedPan={}: {}", maskedPan, e.getMessage());
            return new SimIssuerAuthResponse("DECLINED", "91", null, "Issuer unavailable");
        }
    }
}
