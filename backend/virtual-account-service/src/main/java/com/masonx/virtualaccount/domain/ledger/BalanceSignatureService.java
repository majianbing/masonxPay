package com.masonx.virtualaccount.domain.ledger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes and verifies the HMAC-SHA256 tamper-evident chain on ledger entries.
 *
 * Each entry's signature covers: account_id, entry_seq, amount, direction,
 * balance_after, frozen_balance, transaction_id, and the previous entry's
 * signature. Any direct DB edit to balance or frozen_balance breaks the chain
 * at the next posting.
 *
 * The secret must be managed outside the DB (env var / KMS). Never log it.
 */
@Service
public class BalanceSignatureService {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secretBytes;

    public BalanceSignatureService(@Value("${va.signature.secret}") String secret) {
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String compute(SignatureInput input) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, ALGORITHM));
            byte[] hash = mac.doFinal(input.canonical().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute balance signature", e);
        }
    }

    public boolean verify(SignatureInput input, String expected) {
        return compute(input).equals(expected);
    }
}
