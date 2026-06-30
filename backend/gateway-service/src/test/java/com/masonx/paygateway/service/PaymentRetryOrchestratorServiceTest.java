package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentAttemptType;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.payment.PaymentRequest;
import com.masonx.paygateway.domain.payment.PaymentRequestRepository;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.service.ProviderFailureCodeMapper;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.credentials.SquareCredentials;
import com.masonx.paygateway.provider.credentials.StripeCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentRetryOrchestratorServiceTest {

    @Mock PaymentRequestRepository paymentRequestRepository;
    @Mock PaymentProviderDispatcher dispatcher;
    @Mock ProviderAccountService providerAccountService;
    @Mock FailoverPolicy failoverPolicy;
    @Mock ConnectorCircuitBreaker circuitBreaker;
    @Mock PaymentMetrics metrics;

    private PaymentRetryOrchestratorService orchestrator;
    private final Map<UUID, PaymentRequest> attempts = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        orchestrator = new PaymentRetryOrchestratorService(
                paymentRequestRepository, dispatcher, providerAccountService,
                failoverPolicy, circuitBreaker, new ProviderFailureCodeMapper(),
                metrics, new NoopTransactionManager(), 3, 2);

        lenient().when(paymentRequestRepository.save(any())).thenAnswer(inv -> {
            PaymentRequest attempt = inv.getArgument(0);
            if (attempt.getId() == null) {
                ReflectionTestUtils.setField(attempt, "id", UUID.randomUUID());
            }
            attempts.put(attempt.getId(), attempt);
            return attempt;
        });
        lenient().when(paymentRequestRepository.findById(any()))
                .thenAnswer(inv -> Optional.ofNullable(attempts.get(inv.getArgument(0))));
    }

    @Test
    void execute_allowFallback_retryableFailure_retriesSameAccountBeforeFallbackAndReusesProviderIdempotencyKey() {
        ProviderAccount stripe = account(PaymentProvider.STRIPE);
        ProviderAccount square = account(PaymentProvider.SQUARE);
        PaymentIntent intent = intent();

        when(providerAccountService.loadCredentials(stripe.getId()))
                .thenReturn(new StripeCredentials("sk", "pk"));
        when(providerAccountService.loadCredentials(square.getId()))
                .thenReturn(new SquareCredentials("token", "app", "loc", true));
        when(dispatcher.charge(eq(PaymentProvider.STRIPE), any(), any()))
                .thenReturn(failed("api_error"), failed("api_error"));
        when(dispatcher.charge(eq(PaymentProvider.SQUARE), any(), any()))
                .thenReturn(success("sq-pay"));
        when(failoverPolicy.isRetryable("api_error")).thenReturn(true);

        PaymentRetryOrchestratorService.Result result = orchestrator.execute(
                intent, "pm_123", "card", new RoutePlan(List.of(
                        new RouteCandidate(stripe), new RouteCandidate(square))), PaymentRetryContext.allowFallback());

        assertThat(result.succeeded()).isTrue();
        assertThat(result.usedCandidate().accountId()).isEqualTo(square.getId());
        assertThat(result.providerName()).isEqualTo("SQUARE");
        assertThat(result.attemptCount()).isEqualTo(3);
        verify(dispatcher, times(2)).charge(eq(PaymentProvider.STRIPE), any(), any());
        verify(dispatcher).charge(eq(PaymentProvider.SQUARE), any(), any());
        verify(circuitBreaker, times(2)).recordFailure(stripe.getId(), true);
        verify(circuitBreaker).recordSuccess(square.getId());
        verify(metrics).recordFailover("STRIPE");

        List<PaymentRequest> savedAttempts = List.copyOf(attempts.values());
        assertThat(savedAttempts).hasSize(3);
        assertThat(savedAttempts.get(0).getAttemptNumber()).isEqualTo(1);
        assertThat(savedAttempts.get(0).getAttemptType()).isEqualTo(PaymentAttemptType.PRIMARY);
        assertThat(savedAttempts.get(1).getAttemptNumber()).isEqualTo(2);
        assertThat(savedAttempts.get(1).getAttemptType()).isEqualTo(PaymentAttemptType.SAME_ACCOUNT_RETRY);
        assertThat(savedAttempts.get(2).getAttemptNumber()).isEqualTo(3);
        assertThat(savedAttempts.get(2).getAttemptType()).isEqualTo(PaymentAttemptType.FALLBACK);
        assertThat(savedAttempts.get(0).getConnectorAccountId()).isEqualTo(stripe.getId());
        assertThat(savedAttempts.get(1).getConnectorAccountId()).isEqualTo(stripe.getId());
        assertThat(savedAttempts.get(2).getConnectorAccountId()).isEqualTo(square.getId());
        assertThat(savedAttempts.get(0).getProviderIdempotencyKey())
                .isEqualTo(savedAttempts.get(1).getProviderIdempotencyKey());
        assertThat(savedAttempts.get(2).getProviderIdempotencyKey())
                .isNotEqualTo(savedAttempts.get(0).getProviderIdempotencyKey());
    }

    @Test
    void execute_sameAccountOnly_neverFallsBack() {
        ProviderAccount stripe = account(PaymentProvider.STRIPE);
        ProviderAccount square = account(PaymentProvider.SQUARE);

        when(providerAccountService.loadCredentials(stripe.getId()))
                .thenReturn(new StripeCredentials("sk", "pk"));
        when(dispatcher.charge(eq(PaymentProvider.STRIPE), any(), any()))
                .thenReturn(failed("api_error"), failed("api_error"));
        when(failoverPolicy.isRetryable("api_error")).thenReturn(true);

        PaymentRetryOrchestratorService.Result result = orchestrator.execute(
                intent(), "pm_123", "card", new RoutePlan(List.of(
                        new RouteCandidate(stripe), new RouteCandidate(square))), PaymentRetryContext.sameAccountOnly());

        assertThat(result.succeeded()).isFalse();
        assertThat(result.usedCandidate().accountId()).isEqualTo(stripe.getId());
        assertThat(result.attemptCount()).isEqualTo(2);
        verify(dispatcher, times(2)).charge(eq(PaymentProvider.STRIPE), any(), any());
        verify(dispatcher, never()).charge(eq(PaymentProvider.SQUARE), any(), any());
        verify(providerAccountService, never()).loadCredentials(square.getId());
    }

    @Test
    void execute_allowFallback_nonRetryableFailure_stopsAfterFirstAttempt() {
        ProviderAccount stripe = account(PaymentProvider.STRIPE);
        ProviderAccount square = account(PaymentProvider.SQUARE);

        when(providerAccountService.loadCredentials(stripe.getId()))
                .thenReturn(new StripeCredentials("sk", "pk"));
        when(dispatcher.charge(eq(PaymentProvider.STRIPE), any(), any()))
                .thenReturn(failed("card_declined"));
        when(failoverPolicy.isRetryable("card_declined")).thenReturn(false);

        PaymentRetryOrchestratorService.Result result = orchestrator.execute(
                intent(), "pm_123", "card", new RoutePlan(List.of(
                        new RouteCandidate(stripe), new RouteCandidate(square))), PaymentRetryContext.allowFallback());

        assertThat(result.succeeded()).isFalse();
        assertThat(result.usedCandidate().accountId()).isEqualTo(stripe.getId());
        assertThat(result.attemptCount()).isEqualTo(1);
        verify(dispatcher, never()).charge(eq(PaymentProvider.SQUARE), any(), any());
        verify(circuitBreaker).recordFailure(stripe.getId(), false);
    }

    @Test
    void execute_allowFallback_retryableFailures_respectsConfiguredAttemptLimit() {
        ProviderAccount a = account(PaymentProvider.STRIPE);
        ProviderAccount b = account(PaymentProvider.SQUARE);
        ProviderAccount c = account(PaymentProvider.BRAINTREE);
        ProviderAccount d = account(PaymentProvider.MOLLIE);

        when(providerAccountService.loadCredentials(any()))
                .thenReturn(new StripeCredentials("sk", "pk"));
        when(dispatcher.charge(any(), any(), any())).thenReturn(failed("timeout"));
        when(failoverPolicy.isRetryable("timeout")).thenReturn(true);

        PaymentRetryOrchestratorService.Result result = orchestrator.execute(
                intent(), "pm_123", "card", new RoutePlan(List.of(
                        new RouteCandidate(a), new RouteCandidate(b),
                        new RouteCandidate(c), new RouteCandidate(d))), PaymentRetryContext.allowFallback());

        assertThat(result.succeeded()).isFalse();
        assertThat(result.attemptCount()).isEqualTo(3);
        verify(dispatcher, times(3)).charge(any(), any(), any());
        verify(providerAccountService, never()).loadCredentials(d.getId());
    }

    @Test
    void execute_allowFallback_configuredLimitAboveHardCap_neverExceedsFiveAttempts() {
        orchestrator = new PaymentRetryOrchestratorService(
                paymentRequestRepository, dispatcher, providerAccountService,
                failoverPolicy, circuitBreaker, new ProviderFailureCodeMapper(),
                metrics, new NoopTransactionManager(), 10, 2);

        List<RouteCandidate> candidates = List.of(
                new RouteCandidate(account(PaymentProvider.STRIPE)),
                new RouteCandidate(account(PaymentProvider.STRIPE)),
                new RouteCandidate(account(PaymentProvider.STRIPE)),
                new RouteCandidate(account(PaymentProvider.STRIPE)),
                new RouteCandidate(account(PaymentProvider.STRIPE)),
                new RouteCandidate(account(PaymentProvider.STRIPE))
        );

        when(providerAccountService.loadCredentials(any()))
                .thenReturn(new StripeCredentials("sk", "pk"));
        when(dispatcher.charge(any(), any(), any())).thenReturn(failed("timeout"));
        when(failoverPolicy.isRetryable("timeout")).thenReturn(true);

        PaymentRetryOrchestratorService.Result result = orchestrator.execute(
                intent(), "pm_123", "card", new RoutePlan(candidates), PaymentRetryContext.allowFallback());

        assertThat(result.attemptCount()).isEqualTo(5);
        verify(dispatcher, times(5)).charge(any(), any(), any());
    }

    @Test
    void execute_allowFallback_credentialFailure_recordsFailedAttemptAndStopsAsNonRetryable() {
        ProviderAccount stripe = account(PaymentProvider.STRIPE);

        when(providerAccountService.loadCredentials(stripe.getId()))
                .thenThrow(new IllegalStateException("missing credentials"));
        when(failoverPolicy.isRetryable("connector_not_configured")).thenReturn(false);

        PaymentRetryOrchestratorService.Result result = orchestrator.execute(
                intent(), "pm_123", "card", RoutePlan.single(stripe), PaymentRetryContext.allowFallback());

        assertThat(result.succeeded()).isFalse();
        assertThat(result.lastResult().failureCode()).isEqualTo("connector_not_configured");
        assertThat(attempts.values()).singleElement()
                .satisfies(a -> assertThat(a.getFailureCode()).isEqualTo("connector_not_configured"));
        verify(dispatcher, never()).charge(any(), any(), any());
        verify(circuitBreaker).recordFailure(stripe.getId(), false);
    }

    @Test
    void execute_allowFallback_providerException_canUseFallback() {
        orchestrator = new PaymentRetryOrchestratorService(
                paymentRequestRepository, dispatcher, providerAccountService,
                failoverPolicy, circuitBreaker, new ProviderFailureCodeMapper(),
                metrics, new NoopTransactionManager(), 3, 1);

        ProviderAccount stripe = account(PaymentProvider.STRIPE);
        ProviderAccount square = account(PaymentProvider.SQUARE);

        when(providerAccountService.loadCredentials(stripe.getId()))
                .thenReturn(new StripeCredentials("sk", "pk"));
        when(providerAccountService.loadCredentials(square.getId()))
                .thenReturn(new SquareCredentials("token", "app", "loc", true));
        when(dispatcher.charge(eq(PaymentProvider.STRIPE), any(), any()))
                .thenThrow(new RuntimeException("provider timeout"));
        when(dispatcher.charge(eq(PaymentProvider.SQUARE), any(), any()))
                .thenReturn(success("sq-pay"));
        when(failoverPolicy.isRetryable("provider_exception")).thenReturn(true);

        PaymentRetryOrchestratorService.Result result = orchestrator.execute(
                intent(), "pm_123", "card", new RoutePlan(List.of(
                        new RouteCandidate(stripe), new RouteCandidate(square))), PaymentRetryContext.allowFallback());

        assertThat(result.succeeded()).isTrue();
        assertThat(result.providerName()).isEqualTo("SQUARE");
        verify(circuitBreaker).recordFailure(stripe.getId(), true);
        verify(circuitBreaker).recordSuccess(square.getId());
    }

    @Test
    void execute_outcomeActionNext_skipsSameAccountRetryAndFallsBackImmediately() {
        ProviderAccount stripe = account(PaymentProvider.STRIPE);
        ProviderAccount square = account(PaymentProvider.SQUARE);

        when(providerAccountService.loadCredentials(stripe.getId()))
                .thenReturn(new StripeCredentials("sk", "pk"));
        when(providerAccountService.loadCredentials(square.getId()))
                .thenReturn(new SquareCredentials("token", "app", "loc", true));
        when(dispatcher.charge(eq(PaymentProvider.STRIPE), any(), any()))
                .thenReturn(failed("timeout"));
        when(dispatcher.charge(eq(PaymentProvider.SQUARE), any(), any()))
                .thenReturn(success("sq-pay"));
        when(failoverPolicy.isRetryable("timeout")).thenReturn(true);

        PaymentRetryOrchestratorService.Result result = orchestrator.execute(
                intent(), "pm_123", "card", new RoutePlan(List.of(
                        new RouteCandidate(stripe, Map.of("PROVIDER_TIMEOUT", "next")),
                        new RouteCandidate(square))), PaymentRetryContext.allowFallback());

        assertThat(result.succeeded()).isTrue();
        assertThat(result.providerName()).isEqualTo("SQUARE");
        assertThat(result.attemptCount()).isEqualTo(2);
        verify(dispatcher).charge(eq(PaymentProvider.STRIPE), any(), any());
        verify(dispatcher).charge(eq(PaymentProvider.SQUARE), any(), any());

        List<PaymentRequest> savedAttempts = List.copyOf(attempts.values());
        assertThat(savedAttempts.get(0).getAttemptType()).isEqualTo(PaymentAttemptType.PRIMARY);
        assertThat(savedAttempts.get(1).getAttemptType()).isEqualTo(PaymentAttemptType.FALLBACK);
    }

    @Test
    void execute_outcomeActionStop_stopsEvenWhenFailureIsRetryable() {
        ProviderAccount stripe = account(PaymentProvider.STRIPE);
        ProviderAccount square = account(PaymentProvider.SQUARE);

        when(providerAccountService.loadCredentials(stripe.getId()))
                .thenReturn(new StripeCredentials("sk", "pk"));
        when(dispatcher.charge(eq(PaymentProvider.STRIPE), any(), any()))
                .thenReturn(failed("timeout"));
        when(failoverPolicy.isRetryable("timeout")).thenReturn(true);

        PaymentRetryOrchestratorService.Result result = orchestrator.execute(
                intent(), "pm_123", "card", new RoutePlan(List.of(
                        new RouteCandidate(stripe, Map.of("PROVIDER_TIMEOUT", "stop")),
                        new RouteCandidate(square))), PaymentRetryContext.allowFallback());

        assertThat(result.succeeded()).isFalse();
        assertThat(result.usedCandidate().accountId()).isEqualTo(stripe.getId());
        assertThat(result.attemptCount()).isEqualTo(1);
        verify(dispatcher).charge(eq(PaymentProvider.STRIPE), any(), any());
        verify(dispatcher, never()).charge(eq(PaymentProvider.SQUARE), any(), any());
    }

    private PaymentIntent intent() {
        PaymentIntent intent = new PaymentIntent();
        ReflectionTestUtils.setField(intent, "id", UUID.randomUUID());
        intent.setAmount(1000L);
        intent.setCurrency("USD");
        intent.setCaptureMethod(CaptureMethod.AUTOMATIC);
        return intent;
    }

    private ProviderAccount account(PaymentProvider provider) {
        ProviderAccount account = new ProviderAccount();
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        account.setProvider(provider);
        return account;
    }

    private ChargeResult success(String providerPaymentId) {
        return new ChargeResult(true, providerPaymentId, "{}", null, null,
                false, false, false, null, null, null);
    }

    private ChargeResult failed(String code) {
        return new ChargeResult(false, null, "{}", code, code,
                true, false, false, null, null, null);
    }

    private static final class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
