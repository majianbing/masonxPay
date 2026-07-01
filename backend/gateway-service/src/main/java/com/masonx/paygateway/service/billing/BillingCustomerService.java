package com.masonx.paygateway.service.billing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.billing.BillingCustomer;
import com.masonx.paygateway.domain.billing.BillingCustomerRepository;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethod;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethodRepository;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethodStatus;
import com.masonx.paygateway.domain.instrument.PaymentInstrument;
import com.masonx.paygateway.domain.instrument.PaymentInstrumentRepository;
import com.masonx.paygateway.service.GatewayIdService;
import com.masonx.paygateway.web.dto.AttachCustomerPaymentMethodRequest;
import com.masonx.paygateway.web.dto.BillingCustomerRequest;
import com.masonx.paygateway.web.dto.BillingCustomerResponse;
import com.masonx.paygateway.web.dto.CustomerPaymentMethodResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BillingCustomerService {

    private final BillingCustomerRepository customerRepository;
    private final CustomerPaymentMethodRepository paymentMethodRepository;
    private final PaymentInstrumentRepository paymentInstrumentRepository;
    private final ObjectMapper objectMapper;
    private final GatewayIdService gatewayIdService;

    public BillingCustomerService(BillingCustomerRepository customerRepository,
                                  CustomerPaymentMethodRepository paymentMethodRepository,
                                  PaymentInstrumentRepository paymentInstrumentRepository,
                                  ObjectMapper objectMapper,
                                  GatewayIdService gatewayIdService) {
        this.customerRepository = customerRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.paymentInstrumentRepository = paymentInstrumentRepository;
        this.objectMapper = objectMapper;
        this.gatewayIdService = gatewayIdService;
    }

    @Transactional(readOnly = true)
    public List<BillingCustomerResponse> list(UUID merchantId, ApiKeyMode mode) {
        return customerRepository.findByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, mode).stream()
                .map(this::response)
                .toList();
    }

    @Transactional(readOnly = true)
    public BillingCustomerResponse get(UUID merchantId, ApiKeyMode mode, UUID customerId) {
        return response(loadOwnedCustomer(merchantId, mode, customerId));
    }

    @Transactional
    public BillingCustomerResponse create(UUID merchantId, ApiKeyMode mode, BillingCustomerRequest request) {
        BillingCustomer customer = new BillingCustomer();
        customer.setMerchantId(merchantId);
        customer.setMode(mode);
        apply(customer, request);
        gatewayIdService.assignBillingCustomer(customer);
        return response(customerRepository.save(customer));
    }

    @Transactional
    public BillingCustomerResponse update(UUID merchantId, ApiKeyMode mode, UUID customerId, BillingCustomerRequest request) {
        BillingCustomer customer = loadOwnedCustomer(merchantId, mode, customerId);
        apply(customer, request);
        return response(customerRepository.save(customer));
    }

    @Transactional(readOnly = true)
    public List<CustomerPaymentMethodResponse> listPaymentMethods(UUID merchantId, ApiKeyMode mode, UUID customerId) {
        loadOwnedCustomer(merchantId, mode, customerId);
        return paymentMethodRepository.findByMerchantIdAndCustomerIdOrderByCreatedAtDesc(merchantId, customerId)
                .stream()
                .map(m -> CustomerPaymentMethodResponse.from(m,
                        paymentInstrumentRepository
                                .findByIdAndMerchantId(m.getPaymentInstrumentId(), merchantId)
                                .orElse(null)))
                .toList();
    }

    @Transactional
    public CustomerPaymentMethodResponse attachPaymentMethod(UUID merchantId, ApiKeyMode mode, UUID customerId,
                                                            AttachCustomerPaymentMethodRequest request) {
        loadOwnedCustomer(merchantId, mode, customerId);
        PaymentInstrument instrument = paymentInstrumentRepository
                .findByIdAndMerchantId(request.paymentInstrumentId(), merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Payment instrument not found"));
        if (instrument.getCustomerId() != null && !instrument.getCustomerId().equals(customerId)) {
            throw new IllegalStateException("Payment instrument belongs to another customer");
        }

        CustomerPaymentMethod method = paymentMethodRepository
                .findByMerchantIdAndCustomerIdAndPaymentInstrumentId(
                        merchantId, customerId, request.paymentInstrumentId())
                .orElseGet(CustomerPaymentMethod::new);
        if (method.getId() == null) {
            method.setMerchantId(merchantId);
            method.setCustomerId(customerId);
            method.setPaymentInstrumentId(request.paymentInstrumentId());
        }
        method.setStatus(CustomerPaymentMethodStatus.ACTIVE);

        if (request.defaultMethod()) {
            paymentMethodRepository.clearDefault(merchantId, customerId);
            method.setDefaultMethod(true);
        }

        instrument.setCustomerId(customerId);
        paymentInstrumentRepository.save(instrument);
        return CustomerPaymentMethodResponse.from(paymentMethodRepository.save(method));
    }

    @Transactional
    public CustomerPaymentMethodResponse detachPaymentMethod(UUID merchantId, ApiKeyMode mode, UUID customerId, UUID methodId) {
        loadOwnedCustomer(merchantId, mode, customerId);
        CustomerPaymentMethod method = paymentMethodRepository
                .findByIdAndMerchantIdAndCustomerId(methodId, merchantId, customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer payment method not found"));
        method.setStatus(CustomerPaymentMethodStatus.DETACHED);
        method.setDefaultMethod(false);
        return CustomerPaymentMethodResponse.from(paymentMethodRepository.save(method));
    }

    private BillingCustomer loadOwnedCustomer(UUID merchantId, ApiKeyMode mode, UUID customerId) {
        return customerRepository.findByIdAndMerchantIdAndMode(customerId, merchantId, mode)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));
    }

    private void apply(BillingCustomer customer, BillingCustomerRequest request) {
        customer.setEmail(blankToNull(request.email()));
        customer.setName(blankToNull(request.name()));
        customer.setMetadataJson(serializeMetadata(request.metadata()));
    }

    private BillingCustomerResponse response(BillingCustomer customer) {
        return BillingCustomerResponse.from(customer, parseMetadata(customer.getMetadataJson()));
    }

    private String serializeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Customer metadata is not serializable");
        }
    }

    private Map<String, String> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
