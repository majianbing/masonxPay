package com.masonx.paygateway.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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

        // Log only non-sensitive metadata — never headers (contain HMAC signatures) or body (payment data)
        log.info("[webhook-test] received delivery from={} size={}",
                request.getRemoteAddr(), body == null ? 0 : body.length());

        return ResponseEntity.ok(Map.of("received", "true"));
    }
}
