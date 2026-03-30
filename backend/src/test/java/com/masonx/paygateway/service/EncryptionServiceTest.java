package com.masonx.paygateway.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class EncryptionServiceTest {

    // 32 zero bytes — valid 256-bit key
    private static final String VALID_KEY =
            Base64.getEncoder().encodeToString(new byte[32]);

    private EncryptionService svc() {
        return new EncryptionService(VALID_KEY);
    }

    @Test
    void roundTrip_returnsOriginalPlaintext() {
        String plaintext = "sk_test_super_secret_stripe_key";
        assertThat(svc().decrypt(svc().encrypt(plaintext))).isEqualTo(plaintext);
    }

    @Test
    void encrypt_sameInput_producesDifferentCiphertexts_dueToRandomIv() {
        EncryptionService e = svc();
        String a = e.encrypt("same");
        String b = e.encrypt("same");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void storedFormat_containsColon_separator() {
        String stored = svc().encrypt("payload");
        assertThat(stored).contains(":");
        String[] parts = stored.split(":", 2);
        assertThat(parts).hasSize(2);
        // Both parts must be valid base64
        assertThatCode(() -> Base64.getDecoder().decode(parts[0])).doesNotThrowAnyException();
        assertThatCode(() -> Base64.getDecoder().decode(parts[1])).doesNotThrowAnyException();
    }

    @Test
    void decrypt_tamperedCiphertext_throwsRuntimeException() {
        EncryptionService e = svc();
        String stored = e.encrypt("secret");
        String tampered = stored.substring(0, stored.lastIndexOf(':') + 1) + "AAAAAAAAAAAAAAAA";
        assertThatThrownBy(() -> e.decrypt(tampered))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void decrypt_missingColon_throws() {
        assertThatThrownBy(() -> svc().decrypt("notvalidformat"))
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
