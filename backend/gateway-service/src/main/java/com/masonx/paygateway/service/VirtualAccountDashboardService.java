package com.masonx.paygateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class VirtualAccountDashboardService {

    private static final Logger log = LoggerFactory.getLogger(VirtualAccountDashboardService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String internalToken;

    public VirtualAccountDashboardService(
            @Qualifier("virtualAccountRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${app.virtual-account.base-url:http://localhost:8086}") String baseUrl,
            @Value("${app.virtual-account.internal-token:${INTERNAL_AUTH_TOKEN:internal-dev-secret}}") String internalToken) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
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

    public ResponseEntity<Object> listIssuerPartners(UUID merchantId, String mode, int page, int size) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/issuer-partners")
                .queryParam("merchantId", merchantId)
                .queryParam("mode", normalizedMode(mode))
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", Math.min(Math.max(size, 1), 100))
                .build(true)
                .toUri();
        return exchangeObject(uri, HttpMethod.GET, null);
    }

    public ResponseEntity<Object> listCardPrograms(UUID merchantId, String mode, int page, int size) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/card-programs")
                .queryParam("merchantId", merchantId)
                .queryParam("mode", normalizedMode(mode))
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", Math.min(Math.max(size, 1), 100))
                .build(true)
                .toUri();
        return exchangeObject(uri, HttpMethod.GET, null);
    }

    public ResponseEntity<Object> createCardProgram(UUID merchantId, Map<String, Object> body) {
        Map<String, Object> request = tenantScopedBody(merchantId, body);
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/card-programs")
                .build()
                .toUri();
        return exchangeObject(uri, HttpMethod.POST, request);
    }

    public ResponseEntity<Object> listCardholders(UUID merchantId, String mode, int page, int size) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/cardholders")
                .queryParam("merchantId", merchantId)
                .queryParam("mode", normalizedMode(mode))
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", Math.min(Math.max(size, 1), 100))
                .build(true)
                .toUri();
        return exchangeObject(uri, HttpMethod.GET, null);
    }

    public ResponseEntity<Object> createCardholder(UUID merchantId, Map<String, Object> body) {
        Map<String, Object> request = tenantScopedBody(merchantId, body);
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/cardholders")
                .build()
                .toUri();
        return exchangeObject(uri, HttpMethod.POST, request);
    }

    public ResponseEntity<Object> listCards(UUID merchantId, String mode, int page, int size) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/vcc/cards")
                .queryParam("merchantId", merchantId)
                .queryParam("mode", normalizedMode(mode))
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", Math.min(Math.max(size, 1), 100))
                .build(true)
                .toUri();
        return exchangeObject(uri, HttpMethod.GET, null);
    }

    public ResponseEntity<Object> listAuthorizations(UUID merchantId, String mode, int page, int size) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/vcc/authorizations")
                .queryParam("merchantId", merchantId)
                .queryParam("mode", normalizedMode(mode))
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", Math.min(Math.max(size, 1), 100))
                .build(true)
                .toUri();
        return exchangeObject(uri, HttpMethod.GET, null);
    }

    public ResponseEntity<Object> getCardControls(UUID merchantId, String cardId, String mode) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/vcc/cards/{cardId}/controls")
                .queryParam("merchantId", merchantId)
                .queryParam("mode", normalizedMode(mode))
                .buildAndExpand(cardId)
                .toUri();
        return exchangeObject(uri, HttpMethod.GET, null);
    }

    public ResponseEntity<Object> updateCardControls(UUID merchantId, String cardId, Map<String, Object> body) {
        Map<String, Object> request = tenantScopedBody(merchantId, body);
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/vcc/cards/{cardId}/controls")
                .buildAndExpand(cardId)
                .toUri();
        return exchangeObject(uri, HttpMethod.PUT, request);
    }

    public ResponseEntity<Object> listSettlementReports(
            UUID merchantId, String programId, String mode, int page, int size) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/card-programs/{programId}/settlement-reports")
                .queryParam("merchantId", merchantId)
                .queryParam("mode", normalizedMode(mode))
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", Math.min(Math.max(size, 1), 100))
                .buildAndExpand(programId)
                .toUri();
        return exchangeObject(uri, HttpMethod.GET, null);
    }

    public ResponseEntity<Object> getSettlementReconciliationSummary(
            UUID merchantId, String programId, String mode, LocalDate settlementDate, String currency) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/card-programs/{programId}/settlement-reconciliation-summary")
                .queryParam("merchantId", merchantId)
                .queryParam("mode", normalizedMode(mode))
                .queryParam("settlementDate", settlementDate)
                .queryParam("currency", currency)
                .buildAndExpand(programId)
                .toUri();
        return exchangeObject(uri, HttpMethod.GET, null);
    }

    public ResponseEntity<Object> createCard(UUID merchantId, Map<String, Object> body) {
        Map<String, Object> request = tenantScopedBody(merchantId, body);
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/vcc/cards")
                .build()
                .toUri();
        return exchangeObject(uri, HttpMethod.POST, request);
    }

    public ResponseEntity<Object> fundCard(UUID merchantId, String cardId, Map<String, Object> body) {
        Map<String, Object> request = tenantScopedBody(merchantId, body);
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/vcc/cards/{cardId}/fund")
                .buildAndExpand(cardId)
                .toUri();
        return exchangeObject(uri, HttpMethod.POST, request);
    }

    public ResponseEntity<Object> withdrawCard(UUID merchantId, String cardId, Map<String, Object> body) {
        Map<String, Object> request = tenantScopedBody(merchantId, body);
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/vcc/cards/{cardId}/withdraw")
                .buildAndExpand(cardId)
                .toUri();
        return exchangeObject(uri, HttpMethod.POST, request);
    }

    public ResponseEntity<Object> cardLifecycle(UUID merchantId, String cardId, String action, Map<String, Object> body) {
        Map<String, Object> request = tenantScopedBody(merchantId, body);
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/vcc/cards/{cardId}/{action}")
                .buildAndExpand(cardId, action)
                .toUri();
        return exchangeObject(uri, HttpMethod.POST, request);
    }

    private ResponseEntity<Object> exchangeObject(URI uri, HttpMethod method, Map<String, Object> body) {
        try {
            ResponseEntity<Object> response = restTemplate.exchange(
                    uri, method, new HttpEntity<>(body, headers()), Object.class);
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
        } catch (RestClientResponseException ex) {
            log.info("Virtual Account issuing API returned error: method={} uri={} status={}",
                    method, uri, ex.getStatusCode().value());
            return ResponseEntity.status(ex.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorBody(ex));
        } catch (RestClientException ex) {
            log.info("Virtual Account issuing API unavailable: method={} uri={} error={}",
                    method, uri, ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "title", "Virtual Account service unavailable",
                            "detail", "Virtual Account service is unavailable"));
        }
    }

    private Object errorBody(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return Map.of(
                    "title", ex.getStatusText(),
                    "detail", ex.getMessage());
        }
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of(
                    "title", ex.getStatusText(),
                    "detail", body);
        }
    }

    private Map<String, Object> tenantScopedBody(UUID merchantId, Map<String, Object> body) {
        Map<String, Object> request = new LinkedHashMap<>(body != null ? body : Map.of());
        request.put("merchantId", merchantId.toString());
        if (request.get("mode") instanceof String mode) {
            request.put("mode", normalizedMode(mode));
        } else {
            request.put("mode", "TEST");
        }
        return request;
    }

    private String normalizedMode(String mode) {
        return mode == null || mode.isBlank() ? "TEST" : mode.toUpperCase();
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", internalToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }
}
