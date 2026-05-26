package com.masonx.paygateway.service.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.billing.BillingCustomer;
import com.masonx.paygateway.domain.billing.BillingCustomerRepository;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethod;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethodRepository;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethodStatus;
import com.masonx.paygateway.domain.instrument.InstrumentPortability;
import com.masonx.paygateway.domain.instrument.InstrumentSource;
import com.masonx.paygateway.domain.instrument.InstrumentType;
import com.masonx.paygateway.domain.instrument.PaymentInstrument;
import com.masonx.paygateway.domain.instrument.PaymentInstrumentRepository;
import com.masonx.paygateway.web.dto.AttachCustomerPaymentMethodRequest;
import com.masonx.paygateway.web.dto.BillingCustomerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingCustomerServiceTest {

    private BillingCustomerRepository customerRepository;
    private CustomerPaymentMethodRepository paymentMethodRepository;
    private PaymentInstrumentRepository paymentInstrumentRepository;
    private BillingCustomerService service;

    @BeforeEach
    void setUp() {
        customerRepository = mock(BillingCustomerRepository.class);
        paymentMethodRepository = mock(CustomerPaymentMethodRepository.class);
        paymentInstrumentRepository = mock(PaymentInstrumentRepository.class);
        service = new BillingCustomerService(
                customerRepository,
                paymentMethodRepository,
                paymentInstrumentRepository,
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void createStoresMerchantScopedCustomerAndMetadata() {
        UUID merchantId = UUID.randomUUID();
        when(customerRepository.save(any(BillingCustomer.class))).thenAnswer(invocation -> {
            BillingCustomer customer = invocation.getArgument(0);
            ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());
            return customer;
        });

        var response = service.create(merchantId, new BillingCustomerRequest(
                " customer@example.com ",
                " Jane Customer ",
                Map.of("tier", "gold")));

        ArgumentCaptor<BillingCustomer> captor = ArgumentCaptor.forClass(BillingCustomer.class);
        verify(customerRepository).save(captor.capture());
        BillingCustomer saved = captor.getValue();
        assertThat(saved.getMerchantId()).isEqualTo(merchantId);
        assertThat(saved.getEmail()).isEqualTo("customer@example.com");
        assertThat(saved.getName()).isEqualTo("Jane Customer");
        assertThat(saved.getMetadataJson()).contains("\"tier\":\"gold\"");
        assertThat(response.metadata()).containsEntry("tier", "gold");
    }

    @Test
    void attachPaymentMethodRejectsInstrumentOwnedByAnotherCustomer() {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID otherCustomerId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        when(customerRepository.findByIdAndMerchantId(customerId, merchantId))
                .thenReturn(Optional.of(customer(merchantId, customerId)));
        PaymentInstrument instrument = instrument(merchantId, instrumentId);
        instrument.setCustomerId(otherCustomerId);
        when(paymentInstrumentRepository.findByIdAndMerchantId(instrumentId, merchantId))
                .thenReturn(Optional.of(instrument));

        assertThatThrownBy(() -> service.attachPaymentMethod(
                merchantId, customerId, new AttachCustomerPaymentMethodRequest(instrumentId, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("belongs to another customer");

        verify(paymentMethodRepository, never()).save(any());
    }

    @Test
    void attachPaymentMethodSetsDefaultAndLinksInstrumentToCustomer() {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        when(customerRepository.findByIdAndMerchantId(customerId, merchantId))
                .thenReturn(Optional.of(customer(merchantId, customerId)));
        PaymentInstrument instrument = instrument(merchantId, instrumentId);
        when(paymentInstrumentRepository.findByIdAndMerchantId(instrumentId, merchantId))
                .thenReturn(Optional.of(instrument));
        when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndPaymentInstrumentId(
                merchantId, customerId, instrumentId)).thenReturn(Optional.empty());
        when(paymentMethodRepository.save(any(CustomerPaymentMethod.class))).thenAnswer(invocation -> {
            CustomerPaymentMethod method = invocation.getArgument(0);
            ReflectionTestUtils.setField(method, "id", UUID.randomUUID());
            return method;
        });

        var response = service.attachPaymentMethod(
                merchantId, customerId, new AttachCustomerPaymentMethodRequest(instrumentId, true));

        verify(paymentMethodRepository).clearDefault(merchantId, customerId);
        verify(paymentInstrumentRepository).save(instrument);
        assertThat(instrument.getCustomerId()).isEqualTo(customerId);
        assertThat(response.customerId()).isEqualTo(customerId);
        assertThat(response.paymentInstrumentId()).isEqualTo(instrumentId);
        assertThat(response.defaultMethod()).isTrue();
        assertThat(response.status()).isEqualTo(CustomerPaymentMethodStatus.ACTIVE);
    }

    private BillingCustomer customer(UUID merchantId, UUID customerId) {
        BillingCustomer customer = new BillingCustomer();
        ReflectionTestUtils.setField(customer, "id", customerId);
        customer.setMerchantId(merchantId);
        customer.setEmail("customer@example.com");
        return customer;
    }

    private PaymentInstrument instrument(UUID merchantId, UUID instrumentId) {
        PaymentInstrument instrument = new PaymentInstrument();
        ReflectionTestUtils.setField(instrument, "id", instrumentId);
        instrument.setMerchantId(merchantId);
        instrument.setType(InstrumentType.CARD);
        instrument.setSource(InstrumentSource.PROVIDER_TOKEN);
        instrument.setPortability(InstrumentPortability.PROVIDER_SCOPED);
        instrument.setTokenReference("pm_test");
        return instrument;
    }
}
