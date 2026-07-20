package com.masonx.virtualaccount.domain.ledger;

import com.masonx.virtualaccount.config.LedgerSignatureProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Computes and verifies the HMAC-SHA256 tamper-evident chain on ledger entries.
 *
 * Each entry's signature covers: ledger_account_id, entry_seq, amount, asset,
 * direction, balance_after, transaction_id, previous entry's signature, and the
 * signature key id. Any
 * direct DB edit to balance breaks the chain at the next posting.
 *
 * The secret must be managed outside the DB (env var / KMS). Never log it.
 */
@Service
public class BalanceSignatureService {

    private static final String ALGORITHM = "HmacSHA256";

    private final String activeKeyId;
    private final Map<String, byte[]> keyBytesById;

    @Autowired
    public BalanceSignatureService(LedgerSignatureProperties properties) {
        this(properties.getActiveKeyId(), properties.resolvedKeys());
    }

    public BalanceSignatureService(@Value("${va.signature.secret}") String secret) {
        this(SignatureInput.DEFAULT_SIGNATURE_KEY_ID, Map.of(SignatureInput.DEFAULT_SIGNATURE_KEY_ID, secret));
    }

    private BalanceSignatureService(String activeKeyId, Map<String, String> secretsById) {
        this.activeKeyId = activeKeyId != null && !activeKeyId.isBlank()
                ? activeKeyId
                : SignatureInput.DEFAULT_SIGNATURE_KEY_ID;
        this.keyBytesById = secretsById.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> e.getValue().getBytes(StandardCharsets.UTF_8)));
        if (!keyBytesById.containsKey(this.activeKeyId)) {
            throw new IllegalStateException("Active ledger signature key is not configured: " + this.activeKeyId);
        }
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public String compute(SignatureInput input) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            byte[] secretBytes = keyBytesById.get(input.signatureKeyId());
            if (secretBytes == null) {
                throw new IllegalStateException("Ledger signature key is not configured: " + input.signatureKeyId());
            }
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
