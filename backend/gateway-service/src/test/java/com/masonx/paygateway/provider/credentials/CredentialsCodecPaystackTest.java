package com.masonx.paygateway.provider.credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.service.EncryptionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialsCodecPaystackTest {

    @Test
    void encodeDecode_keepsSecretEncryptedAndExposesOnlyClientSafeKey() {
        CredentialsCodec codec = new CredentialsCodec(
                new EncryptionService("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
                new ObjectMapper());
        ProviderAccount account = new ProviderAccount();
        account.setProvider(PaymentProvider.PAYSTACK);
        account.setMode(ApiKeyMode.TEST);

        codec.encode(new PaystackCredentials("sk_test_secret", "pk_test_public", true), account);

        assertThat(account.getEncryptedCredentials()).doesNotContain("sk_test_secret");
        assertThat(account.getProviderConfig()).contains("pk_test_public");
        assertThat(codec.clientKeyFor(account)).isEqualTo("pk_test_public");

        PaystackCredentials decoded = (PaystackCredentials) codec.decode(account);
        assertThat(decoded.secretKey()).isEqualTo("sk_test_secret");
        assertThat(decoded.publicKey()).isEqualTo("pk_test_public");
        assertThat(decoded.sandbox()).isTrue();
    }
}
