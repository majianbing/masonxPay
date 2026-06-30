package com.masonx.rail.iso20022;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * HTTP client for the ISO 20022 bank-simulator endpoints.
 *
 * <p>Two operations:
 * <ul>
 *   <li>{@code POST /bank-sim/pain.001} — submit a credit transfer; receives pain.002.
 *   <li>{@code GET  /bank-sim/payments/{endToEndId}/messages} — poll for async messages
 *       (pacs.002, pacs.004, camt.054) queued by the simulator since the last poll.
 * </ul>
 */
@Component
public class BankRailHttpClient {

    private static final Logger log = LoggerFactory.getLogger(BankRailHttpClient.class);

    private final RestTemplate restTemplate;

    public BankRailHttpClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Submits a pain.001 XML to the bank-sim and returns the pain.002 XML response.
     *
     * @param xml     ISO 20022 pain.001 document
     * @param baseUrl e.g. {@code http://rail-simulator:9090}
     * @return pain.002 XML body from the simulator
     */
    public String sendPain001(String xml, String baseUrl) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        headers.setAccept(List.of(MediaType.APPLICATION_XML));

        String url = baseUrl + "/bank-sim/pain.001";
        log.debug("Posting pain.001 to {}", url);

        String response = restTemplate.postForObject(url, new HttpEntity<>(xml, headers), String.class);
        return response;
    }

    /**
     * Fetches queued async messages for a given {@code endToEndId}.
     * The simulator drains and returns all messages queued since the last poll
     * (pacs.002, pacs.004, camt.054). Returns an empty list if none are ready.
     *
     * @param endToEndId ISO 20022 end-to-end ID used as correlation key
     * @param baseUrl    e.g. {@code http://rail-simulator:9090}
     * @return list of XML message strings (may be empty)
     */
    @SuppressWarnings("unchecked")
    public List<String> fetchMessages(String endToEndId, String baseUrl) {
        String url = baseUrl + "/bank-sim/payments/" + endToEndId + "/messages";
        log.debug("Polling bank-sim messages for endToEndId={}", endToEndId);
        try {
            String[] messages = restTemplate.getForObject(url, String[].class);
            return messages != null ? Arrays.asList(messages) : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to fetch bank-sim messages for endToEndId={}: {}", endToEndId, e.getMessage());
            return Collections.emptyList();
        }
    }
}
