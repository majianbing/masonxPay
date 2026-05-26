package com.masonx.paygateway.service.routing;

import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountCapability;
import com.masonx.paygateway.domain.connector.ProviderAccountCapabilityRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.web.dto.ProviderAccountCapabilityRequest;
import com.masonx.paygateway.web.dto.ProviderAccountCapabilityResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderAccountCapabilityManagementServiceTest {

    @Mock ProviderAccountRepository providerAccountRepository;
    @Mock ProviderAccountCapabilityRepository capabilityRepository;

    private ProviderAccountCapabilityManagementService service;

    @BeforeEach
    void setUp() {
        service = new ProviderAccountCapabilityManagementService(providerAccountRepository, capabilityRepository);
    }

    @Test
    void replace_normalizesAndPersistsCapabilitiesForOwnedAccount() {
        UUID merchantId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(providerAccountRepository.findByIdAndMerchantId(accountId, merchantId))
                .thenReturn(Optional.of(account(accountId)));
        when(capabilityRepository.findAllByMerchantIdAndProviderAccountIdOrderByCreatedAtAsc(merchantId, accountId))
                .thenReturn(List.of(existing(merchantId, accountId)));
        when(capabilityRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<ProviderAccountCapabilityResponse> responses = service.replace(merchantId, accountId, List.of(
                new ProviderAccountCapabilityRequest(
                        " CARD ", " us ", " usd ", 100L, 5000L,
                        true, true, true, true, false,
                        true, false, false, false, true)));

        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.paymentMethodType()).isEqualTo("card");
            assertThat(response.country()).isEqualTo("US");
            assertThat(response.currency()).isEqualTo("USD");
            assertThat(response.minAmount()).isEqualTo(100L);
            assertThat(response.maxAmount()).isEqualTo(5000L);
            assertThat(response.supportsProviderToken()).isTrue();
        });
        verify(capabilityRepository).deleteAll(anyList());
    }

    @Test
    void replace_rejectsInvalidAmountRange() {
        UUID merchantId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(providerAccountRepository.findByIdAndMerchantId(accountId, merchantId))
                .thenReturn(Optional.of(account(accountId)));
        when(capabilityRepository.findAllByMerchantIdAndProviderAccountIdOrderByCreatedAtAsc(merchantId, accountId))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.replace(merchantId, accountId, List.of(
                new ProviderAccountCapabilityRequest(
                        "card", null, null, 5000L, 100L,
                        true, true, true, false, false,
                        true, false, false, false, true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minAmount");
    }

    private ProviderAccount account(UUID accountId) {
        ProviderAccount account = new ProviderAccount();
        ReflectionTestUtils.setField(account, "id", accountId);
        return account;
    }

    private ProviderAccountCapability existing(UUID merchantId, UUID accountId) {
        ProviderAccountCapability capability = new ProviderAccountCapability();
        capability.setMerchantId(merchantId);
        capability.setProviderAccountId(accountId);
        capability.setPaymentMethodType("card");
        return capability;
    }
}
