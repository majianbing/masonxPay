package com.masonx.paygateway.domain.billing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class CustomerPaymentMethodRepositoryTest {

    @Autowired
    private CustomerPaymentMethodRepository repository;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void clearDefaultRunsWithoutCallerTransaction() {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        CustomerPaymentMethod defaultMethod = method(merchantId, customerId, true);
        CustomerPaymentMethod secondaryMethod = method(merchantId, customerId, false);
        repository.save(defaultMethod);
        repository.save(secondaryMethod);

        repository.clearDefault(merchantId, customerId);

        assertThat(repository.findByMerchantIdAndCustomerIdOrderByCreatedAtDesc(merchantId, customerId))
                .extracting(CustomerPaymentMethod::isDefaultMethod)
                .containsOnly(false);
    }

    private CustomerPaymentMethod method(UUID merchantId, UUID customerId, boolean defaultMethod) {
        CustomerPaymentMethod method = new CustomerPaymentMethod();
        method.setMerchantId(merchantId);
        method.setCustomerId(customerId);
        method.setPaymentInstrumentId(UUID.randomUUID());
        method.setStatus(CustomerPaymentMethodStatus.ACTIVE);
        method.setDefaultMethod(defaultMethod);
        return method;
    }
}
