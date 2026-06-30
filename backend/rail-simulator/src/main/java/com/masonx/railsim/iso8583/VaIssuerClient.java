package com.masonx.railsim.iso8583;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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

    private static final String TOKEN_HEADER = "X-Internal-Token";

    private final String vaIssuerUrl;
    private final String internalAuthToken;
    private final RestTemplate restTemplate;

    public VaIssuerClient(
            @Value("${railsim.va-issuer-url:http://localhost:8086/internal/issuer/authorize}")
            String vaIssuerUrl,
            @Value("${railsim.internal-auth-token:internal-dev-secret}")
            String internalAuthToken) {
        this.vaIssuerUrl       = vaIssuerUrl;
        this.internalAuthToken = internalAuthToken;
        this.restTemplate      = new RestTemplate();
    }

    /**
     * Calls the VA issuer endpoint and returns its decision.
     * On any HTTP or network error, returns a decline with DE39=91 (issuer unavailable).
     */
    public SimIssuerAuthResponse authorize(String maskedPan, BigDecimal amount,
                                           String currency, String stan, String rrn) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TOKEN_HEADER, internalAuthToken);
        var entity = new HttpEntity<>(new SimIssuerAuthRequest(maskedPan, amount, currency, stan, rrn), headers);
        try {
            var resp = restTemplate.postForEntity(vaIssuerUrl, entity, SimIssuerAuthResponse.class);
            SimIssuerAuthResponse body = resp.getBody();
            if (body == null) {
                log.error("VA issuer returned null response for maskedPan={}", maskedPan);
                return new SimIssuerAuthResponse("DECLINED", "91", null, "Null response from issuer");
            }
            return body;
        } catch (RestClientException e) {
            log.error("VA issuer call failed for maskedPan={}: {}", maskedPan, e.getMessage());
            return new SimIssuerAuthResponse("DECLINED", "91", null, "Issuer unavailable");
        }
    }
}
