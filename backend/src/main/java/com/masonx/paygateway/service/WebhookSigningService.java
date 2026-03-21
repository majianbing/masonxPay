package com.masonx.paygateway.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class WebhookSigningService {

    private static final String HMAC_ALGO = "HmacSHA256";

    /**
     * Builds the Stripe-style signature header value:
     *   t=<timestamp>,v1=<hex(HMAC-SHA256(secret, "t=<timestamp>.<body>"))>
     */
    public String buildSignatureHeader(String signingSecret, long timestamp, String body) {
        String signedPayload = "t=" + timestamp + "." + body;
        String signature = hmacHex(signingSecret, signedPayload);
        return "t=" + timestamp + ",v1=" + signature;
    }

    private String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
