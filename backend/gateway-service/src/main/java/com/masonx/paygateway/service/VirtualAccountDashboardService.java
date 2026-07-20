package com.masonx.paygateway.service;

import com.masonx.paygateway.web.dto.VirtualAccountAccountsResponse;
import com.masonx.paygateway.web.dto.VirtualAccountLedgerEntryResponse;
import com.masonx.paygateway.web.dto.VirtualAccountLedgerAccountResponse;
import com.masonx.paygateway.web.dto.VirtualAccountPageResponse;
import com.masonx.paygateway.web.dto.VirtualAccountStatementResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class VirtualAccountDashboardService {

    private static final Logger log = LoggerFactory.getLogger(VirtualAccountDashboardService.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalToken;

    public VirtualAccountDashboardService(
            @Qualifier("virtualAccountRestTemplate") RestTemplate restTemplate,
            @Value("${app.virtual-account.base-url:http://localhost:8086}") String baseUrl,
            @Value("${app.virtual-account.internal-token:${INTERNAL_AUTH_TOKEN:internal-dev-secret}}") String internalToken) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.internalToken = internalToken;
    }

    public VirtualAccountAccountsResponse listAccounts(UUID merchantId, String mode, int page, int size) {
        int cappedSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/va/accounts")
                .queryParam("merchantId", merchantId)
                .queryParam("mode", normalizedMode(mode))
                .queryParam("page", safePage)
                .queryParam("size", cappedSize)
                .build(true)
                .toUri();

        try {
            ResponseEntity<VirtualAccountPageResponse<VirtualAccountLedgerAccountResponse>> response =
                    restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers()),
                            new ParameterizedTypeReference<>() {});
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return VirtualAccountAccountsResponse.available(response.getBody());
            }
            return VirtualAccountAccountsResponse.unavailable(
                    "Virtual Account service returned " + response.getStatusCode().value(),
                    safePage, cappedSize);
        } catch (RestClientException ex) {
            log.info("Virtual Account service unavailable for merchant={} page={}: {}",
                    merchantId, safePage, ex.getMessage());
            return VirtualAccountAccountsResponse.unavailable("Virtual Account service is unavailable",
                    safePage, cappedSize);
        }
    }

    public VirtualAccountLedgerAccountResponse getAccount(UUID merchantId, String accountId) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/va/accounts/{accountId}")
                .queryParam("merchantId", merchantId)
                .buildAndExpand(accountId)
                .toUri();
        return restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers()),
                VirtualAccountLedgerAccountResponse.class).getBody();
    }

    public VirtualAccountPageResponse<VirtualAccountLedgerEntryResponse> listEntries(
            UUID merchantId, String accountId, String mode, int page, int size) {
        int cappedSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/ledger/accounts/{accountId}/entries")
                .queryParam("merchantId", merchantId)
                .queryParam("mode", normalizedMode(mode))
                .queryParam("page", safePage)
                .queryParam("size", cappedSize)
                .buildAndExpand(accountId)
                .toUri();
        return restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers()),
                new ParameterizedTypeReference<VirtualAccountPageResponse<VirtualAccountLedgerEntryResponse>>() {})
                .getBody();
    }

    public VirtualAccountStatementResponse getStatement(
            UUID merchantId, String accountId, String mode, LocalDate from, LocalDate to) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/ledger/accounts/{accountId}/statement")
                .queryParam("merchantId", merchantId)
                .queryParam("mode", normalizedMode(mode))
                .queryParam("from", from)
                .queryParam("to", to)
                .buildAndExpand(accountId)
                .toUri();
        try {
            ResponseEntity<VirtualAccountStatementResponse> response =
                    restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers()),
                            VirtualAccountStatementResponse.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody().asAvailable();
            }
            return VirtualAccountStatementResponse.unavailable(
                    "Virtual Account service returned " + response.getStatusCode().value());
        } catch (RestClientException ex) {
            log.info("Virtual Account statement unavailable for merchant={} account={}: {}",
                    merchantId, accountId, ex.getMessage());
            return VirtualAccountStatementResponse.unavailable("Virtual Account service is unavailable");
        }
    }

    private String normalizedMode(String mode) {
        return mode == null || mode.isBlank() ? "TEST" : mode.toUpperCase();
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", internalToken);
        return headers;
    }
}
