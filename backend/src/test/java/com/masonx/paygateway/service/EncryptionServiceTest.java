package com.masonx.paygateway.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class EncryptionServiceTest {

    private static final String VALID_KEY =
            Base64.getEncoder().encodeToString(new byte[32]);

    private static final EncryptionService SVC = new EncryptionService(VALID_KEY);

    @Test
    void roundTrip_returnsOriginalPlaintext() {
        String plaintext = "sk_test_super_secret_stripe_key";
        assertThat(SVC.decrypt(SVC.encrypt(plaintext))).isEqualTo(plaintext);
    }

    @Test
    void encrypt_sameInput_producesDifferentCiphertexts_dueToRandomIv() {
        String a = SVC.encrypt("same");
        String b = SVC.encrypt("same");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void storedFormat_containsColon_separator() {
        String stored = SVC.encrypt("payload");
        assertThat(stored).contains(":");
        String[] parts = stored.split(":", 2);
        assertThat(parts).hasSize(2);
        // Both parts must be valid base64
        assertThatCode(() -> Base64.getDecoder().decode(parts[0])).doesNotThrowAnyException();
        assertThatCode(() -> Base64.getDecoder().decode(parts[1])).doesNotThrowAnyException();
    }

    @Test
    void decrypt_tamperedCiphertext_throwsRuntimeException() {
        String stored = SVC.encrypt("secret");
        String tampered = stored.substring(0, stored.lastIndexOf(':') + 1) + "AAAAAAAAAAAAAAAA";
        assertThatThrownBy(() -> SVC.decrypt(tampered))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void decrypt_missingColon_throws() {
        assertThatThrownBy(() -> SVC.decrypt("notvalidformat"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void constructor_shortKey_throws() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new EncryptionService(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32-byte");
    }
}
