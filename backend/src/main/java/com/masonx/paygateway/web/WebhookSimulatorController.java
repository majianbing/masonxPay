package com.masonx.paygateway.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Test endpoint for verifying webhook delivery.
 * Register http(s)://your-host/pub/webhook-test as a webhook destination,
 * then trigger a payment event — the payload and headers will appear in the logs.
 */
@RestController
@RequestMapping("/pub/webhook-test")
public class WebhookSimulatorController {

    private static final Logger log = LoggerFactory.getLogger(WebhookSimulatorController.class);

    @PostMapping
    public ResponseEntity<Map<String, String>> receive(
            @RequestBody String body,
            HttpServletRequest request) {

        Map<String, String> headers = Collections.list(request.getHeaderNames()).stream()
                .collect(Collectors.toMap(h -> h, request::getHeader));

        log.info("[webhook-test] received delivery\n  headers: {}\n  body: {}", headers, body);

        return ResponseEntity.ok(Map.of("received", "true"));
    }
}
