package com.masonx.railsim.iso8583;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP client that calls virtual-account-service's authorization decision endpoint
 * for BIN 999999 cards.
 *
 * <p>BIN 999999 identifies VA-issued VCCs. The card network sim acts as the card
 * network + issuer processor but delegates the authorization decision to the VA
 * service (the program manager).
 *
 * <p>The VA endpoint is idempotent on {@code authorizationId}, so one retry with
 * the same id is safe: if the first attempt timed out after VA committed a hold,
 * the retry replays the original decision instead of leaving an orphaned hold
 * behind a blind DE39=91 decline.
 */
@Component
public class VaIssuerClient {

    private static final Logger log = LoggerFactory.getLogger(VaIssuerClient.class);

    static final String REASON_ISSUER_UNAVAILABLE = "ISSUER_UNAVAILABLE";

    private static final String TOKEN_HEADER = "X-Internal-Token";
    private static final int MAX_ATTEMPTS = 2;

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
     * Calls the VA decision endpoint and returns its decision, retrying once with
     * the same authorizationId on transport failure. On persistent failure,
     * returns a decline with reason {@link #REASON_ISSUER_UNAVAILABLE}.
     */
    public SimIssuerAuthResponse authorize(SimIssuerAuthRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TOKEN_HEADER, internalAuthToken);
        var entity = new HttpEntity<>(request, headers);

        RestClientException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                var resp = restTemplate.postForEntity(vaIssuerUrl, entity, SimIssuerAuthResponse.class);
                SimIssuerAuthResponse body = resp.getBody();
                if (body == null) {
                    log.error("VA issuer returned null response for authorizationId={}",
                            request.authorizationId());
                    return new SimIssuerAuthResponse("DECLINED", REASON_ISSUER_UNAVAILABLE);
                }
                return body;
            } catch (RestClientException e) {
                lastFailure = e;
                log.warn("VA issuer call attempt {}/{} failed for authorizationId={}: {}",
                        attempt, MAX_ATTEMPTS, request.authorizationId(), e.getMessage());
            }
        }
        log.error("VA issuer unreachable after {} attempts for authorizationId={}: {}",
                MAX_ATTEMPTS, request.authorizationId(), lastFailure.getMessage());
        return new SimIssuerAuthResponse("DECLINED", REASON_ISSUER_UNAVAILABLE);
    }
}
