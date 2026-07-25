package com.masonx.paygateway.provider.credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.service.EncryptionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialsCodecFlutterwaveTest {

    @Test
    void encodeDecode_keepsSecretEncryptedAndExposesOnlyClientSafeKey() {
        CredentialsCodec codec = new CredentialsCodec(
                new EncryptionService("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
                new ObjectMapper());
        ProviderAccount account = new ProviderAccount();
        account.setProvider(PaymentProvider.FLUTTERWAVE);
        account.setMode(ApiKeyMode.TEST);

        codec.encode(new FlutterwaveCredentials(
                "FLWSECK_TEST-secret",
                "FLWPUBK_TEST-public",
                "webhook-hash",
                true), account);

        assertThat(account.getEncryptedCredentials()).doesNotContain("FLWSECK_TEST-secret");
        assertThat(account.getProviderConfig()).contains("FLWPUBK_TEST-public");
        assertThat(codec.clientKeyFor(account)).isEqualTo("FLWPUBK_TEST-public");

        FlutterwaveCredentials decoded = (FlutterwaveCredentials) codec.decode(account);
        assertThat(decoded.secretKey()).isEqualTo("FLWSECK_TEST-secret");
        assertThat(decoded.publicKey()).isEqualTo("FLWPUBK_TEST-public");
        assertThat(decoded.webhookHash()).isEqualTo("webhook-hash");
        assertThat(decoded.sandbox()).isTrue();
    }
}
