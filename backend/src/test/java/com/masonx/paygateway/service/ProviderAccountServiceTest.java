package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountCapability;
import com.masonx.paygateway.domain.connector.ProviderAccountCapabilityRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.provider.credentials.SimulatorCredentials;
import com.masonx.paygateway.provider.credentials.StripeCredentials;
import com.masonx.paygateway.provider.simulator.ProviderSimulatorProperties;
import com.masonx.paygateway.web.dto.CreateProviderAccountRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderAccountServiceTest {

    @Mock ProviderAccountRepository repo;
    @Mock ProviderAccountCapabilityRepository capabilityRepository;
    @Mock CredentialsCodec codec;
    @Mock ProviderSimulatorProperties simulatorProperties;

    private ProviderAccountService service;

    @BeforeEach
    void setUp() {
        service = new ProviderAccountService(repo, capabilityRepository, codec, simulatorProperties);
        when(repo.save(any())).thenAnswer(inv -> {
            ProviderAccount account = inv.getArgument(0);
            if (account.getId() == null) {
                ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
            }
            return account;
        });
    }

    @Test
    void create_stripe_seedsDefaultCardCapability() {
        UUID merchantId = UUID.randomUUID();
        CreateProviderAccountRequest request = stripeRequest();
        when(codec.fromRequest(PaymentProvider.STRIPE, request, ApiKeyMode.TEST))
                .thenReturn(new StripeCredentials("sk", "pk"));
        when(codec.clientKeyFor(any())).thenReturn("pk");

        service.create(merchantId, request);

        ArgumentCaptor<ProviderAccountCapability> captor =
                ArgumentCaptor.forClass(ProviderAccountCapability.class);
        verify(capabilityRepository).save(captor.capture());
        ProviderAccountCapability capability = captor.getValue();
        assertThat(capability.getMerchantId()).isEqualTo(merchantId);
        assertThat(capability.getProviderAccountId()).isNotNull();
        assertThat(capability.getPaymentMethodType()).isEqualTo("card");
        assertThat(capability.isSupportsProviderToken()).isTrue();
        assertThat(capability.isSupportsManualCapture()).isTrue();
        assertThat(capability.isSupports3ds()).isTrue();
        assertThat(capability.isSupportsRedirect()).isFalse();
    }

    @Test
    void create_simulator_seedsTestCardCapabilityWithoutExternalProviderDependency() {
        UUID merchantId = UUID.randomUUID();
        CreateProviderAccountRequest request = simulatorRequest();
        when(simulatorProperties.isEnabled()).thenReturn(true);
        when(codec.fromRequest(PaymentProvider.SIMULATOR, request, ApiKeyMode.TEST))
                .thenReturn(new SimulatorCredentials(true, 1.0));
        when(codec.clientKeyFor(any())).thenReturn("mason-simulator");

        service.create(merchantId, request);

        ArgumentCaptor<ProviderAccountCapability> captor =
                ArgumentCaptor.forClass(ProviderAccountCapability.class);
        verify(capabilityRepository).save(captor.capture());
        ProviderAccountCapability capability = captor.getValue();
        assertThat(capability.getMerchantId()).isEqualTo(merchantId);
        assertThat(capability.getPaymentMethodType()).isEqualTo("card");
        assertThat(capability.isSupportsProviderToken()).isTrue();
        assertThat(capability.isSupportsManualCapture()).isTrue();
        assertThat(capability.isSupports3ds()).isTrue();
        assertThat(capability.isSupportsRedirect()).isFalse();
    }

    private CreateProviderAccountRequest stripeRequest() {
        return new CreateProviderAccountRequest(
                "STRIPE", "TEST", "Stripe test", true, 1, 0, 0,
                "sk", "pk",
                null, null, null,
                null, null, null,
                null,
                null
        );
    }

    private CreateProviderAccountRequest simulatorRequest() {
        return new CreateProviderAccountRequest(
                "SIMULATOR", "TEST", "Simulator", true, 1, 0, 0,
                null, null,
                null, null, null,
                null, null, null,
                null,
                100.0
        );
    }
}
