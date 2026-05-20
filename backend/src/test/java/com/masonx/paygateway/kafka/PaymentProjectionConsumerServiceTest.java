package com.masonx.paygateway.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.projection.PaymentReadModel;
import com.masonx.paygateway.domain.projection.PaymentReadModelRepository;
import com.masonx.paygateway.domain.projection.ProjectionEventStatus;
import com.masonx.paygateway.domain.projection.ProjectionProcessedEvent;
import com.masonx.paygateway.domain.projection.ProjectionProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentProjectionConsumerServiceTest {

    private PaymentReadModelRepository readModelRepository;
    private ProjectionProcessedEventRepository processedEventRepository;
    private PaymentProjectionConsumerService service;

    @BeforeEach
    void setUp() {
        readModelRepository = mock(PaymentReadModelRepository.class);
        processedEventRepository = mock(ProjectionProcessedEventRepository.class);
        service = new PaymentProjectionConsumerService(
                new ObjectMapper().findAndRegisterModules(),
                readModelRepository,
                processedEventRepository,
                new NoopTransactionManager());
    }

    @Test
    void consumePaymentLifecycleEvent_paymentSucceeded_upsertsReadModelAndRecordsEvent() {
        UUID outboxEventId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        when(processedEventRepository.existsById(outboxEventId)).thenReturn(false);
        when(readModelRepository.findById(paymentIntentId)).thenReturn(Optional.empty());

        service.consumePaymentLifecycleEvent("""
                {
                  "outboxEventId":"%s",
                  "merchantId":"%s",
                  "eventType":"payment_intent.succeeded",
                  "resourceId":"%s",
                  "createdAt":"2026-05-17T00:00:00Z",
                  "payload":{
                    "id":"%s",
                    "merchantId":"%s",
                    "mode":"TEST",
                    "amount":4200,
                    "currency":"USD",
                    "status":"SUCCEEDED",
                    "captureMethod":"AUTOMATIC",
                    "resolvedProvider":"STRIPE",
                    "providerPaymentId":"pi_123",
                    "idempotencyKey":"idem-1",
                    "orderId":"order-1",
                    "description":"Test order",
                    "billingDetails":{"email":"buyer@example.com"},
                    "createdAt":"2026-05-17T00:00:00Z",
                    "updatedAt":"2026-05-17T00:00:01Z"
                  }
                }
                """.formatted(outboxEventId, merchantId, paymentIntentId, paymentIntentId, merchantId));

        ArgumentCaptor<PaymentReadModel> modelCaptor = ArgumentCaptor.forClass(PaymentReadModel.class);
        verify(readModelRepository).save(modelCaptor.capture());
        PaymentReadModel model = modelCaptor.getValue();
        assertThat(model.getPaymentIntentId()).isEqualTo(paymentIntentId);
        assertThat(model.getMerchantId()).isEqualTo(merchantId);
        assertThat(model.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(model.getResolvedProvider()).isEqualTo("STRIPE");
        assertThat(model.getBillingEmail()).isEqualTo("buyer@example.com");
        assertThat(model.getSearchText()).contains("pi_123", "order-1", "buyer@example.com");

        ArgumentCaptor<ProjectionProcessedEvent> eventCaptor = ArgumentCaptor.forClass(ProjectionProcessedEvent.class);
        verify(processedEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getOutboxEventId()).isEqualTo(outboxEventId);
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo(ProjectionEventStatus.PROCESSED);
    }

    @Test
    void consumePaymentLifecycleEvent_duplicateEvent_skipsProjection() {
        UUID outboxEventId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        when(processedEventRepository.existsById(outboxEventId)).thenReturn(true);

        service.consumePaymentLifecycleEvent("""
                {"outboxEventId":"%s","merchantId":"%s","eventType":"payment_intent.succeeded","resourceId":"%s","createdAt":"2026-05-17T00:00:00Z","payload":{"id":"%s"}}
                """.formatted(outboxEventId, merchantId, paymentIntentId, paymentIntentId));

        verify(readModelRepository, never()).save(any());
    }

    @Test
    void consumePaymentLifecycleEvent_refundSucceeded_updatesExistingProjection() {
        UUID outboxEventId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        PaymentReadModel existing = new PaymentReadModel();
        existing.setPaymentIntentId(paymentIntentId);
        existing.setMerchantId(merchantId);
        existing.setMode("TEST");
        existing.setAmount(10_000L);
        existing.setCurrency("USD");
        existing.setStatus("SUCCEEDED");
        when(processedEventRepository.existsById(outboxEventId)).thenReturn(false);
        when(readModelRepository.findByPaymentIntentIdAndMerchantId(paymentIntentId, merchantId))
                .thenReturn(Optional.of(existing));

        service.consumePaymentLifecycleEvent("""
                {
                  "outboxEventId":"%s",
                  "merchantId":"%s",
                  "eventType":"refund.succeeded",
                  "resourceId":"%s",
                  "createdAt":"2026-05-17T00:00:00Z",
                  "payload":{
                    "id":"%s",
                    "paymentIntentId":"%s",
                    "merchantId":"%s",
                    "amount":2500,
                    "currency":"USD",
                    "status":"SUCCEEDED"
                  }
                }
                """.formatted(outboxEventId, merchantId, refundId, refundId, paymentIntentId, merchantId));

        ArgumentCaptor<PaymentReadModel> modelCaptor = ArgumentCaptor.forClass(PaymentReadModel.class);
        verify(readModelRepository).save(modelCaptor.capture());
        assertThat(modelCaptor.getValue().getRefundedAmountSucceeded()).isEqualTo(2500L);
        assertThat(modelCaptor.getValue().getLastRefundId()).isEqualTo(refundId);
        assertThat(modelCaptor.getValue().getLastRefundStatus()).isEqualTo("SUCCEEDED");
    }

    private static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
