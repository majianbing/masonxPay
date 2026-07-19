package com.masonx.common.card;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic token id for local simulator PANs.
 *
 * <p>This is not a production card vault token. It exists so non-PCI simulator
 * components can identify test cards without using masked PAN as identity.
 */
public final class SimulatorCardTokenId {

    private static final String PREFIX = "ctok_";
    private static final int HEX_CHARS = 48;

    private SimulatorCardTokenId() {
    }

    public static String fromPan(String pan) {
        if (pan == null || pan.isBlank()) {
            throw new IllegalArgumentException("pan must not be blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("masonxpay-sim-card:" + pan).getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(hash).substring(0, HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }
}
