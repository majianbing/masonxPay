package com.masonx.paygateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.outbox.OutboxEvent;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.payment.Refund;
import com.masonx.paygateway.domain.payment.RefundRepository;
import com.masonx.paygateway.domain.payment.RefundStatus;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.PaymentProviderService;
import com.masonx.paygateway.provider.RefundRequest;
import com.masonx.paygateway.provider.RefundResult;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.provider.credentials.StripeCredentials;
import com.masonx.paygateway.web.dto.CreateRefundRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID INTENT_ID = UUID.randomUUID();

    @Test
    void createRefund_amountGreaterThanPayment_throwsBeforeProviderCall() {
        PaymentIntent intent = succeededIntent(10_000L);
        LockingPaymentIntentRepository paymentIntents = new LockingPaymentIntentRepository(intent);
        InMemoryRefundRepository refunds = new InMemoryRefundRepository();
        FakeProvider provider = new FakeProvider();

        RefundService service = service(paymentIntents, refunds, provider);

        assertThatThrownBy(() -> service.createRefund(
                MERCHANT_ID, INTENT_ID, new CreateRefundRequest(10_001L, "requested_by_customer")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds available refundable amount")
                .hasMessageContaining("available: 10000");

        assertThat(provider.refundCalls).isZero();
        assertThat(refunds.saved).isEmpty();
    }

    @Test
    void createRefund_existingRefundsPlusRequestedExceedsRemaining_throwsBeforeProviderCall() {
        PaymentIntent intent = succeededIntent(10_000L);
        LockingPaymentIntentRepository paymentIntents = new LockingPaymentIntentRepository(intent);
        InMemoryRefundRepository refunds = new InMemoryRefundRepository();
        refunds.insertPending(INTENT_ID, MERCHANT_ID, 6_000L);
        FakeProvider provider = new FakeProvider();

        RefundService service = service(paymentIntents, refunds, provider);

        assertThatThrownBy(() -> service.createRefund(
                MERCHANT_ID, INTENT_ID, new CreateRefundRequest(5_000L, "requested_by_customer")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds available refundable amount")
                .hasMessageContaining("available: 4000");

        assertThat(provider.refundCalls).isZero();
        assertThat(refunds.activeSum()).isEqualTo(6_000L);
    }

    @Test
    void createRefund_concurrentPartialRefunds_preventsOverRefund() throws Exception {
        PaymentIntent intent = succeededIntent(10_000L);
        LockingPaymentIntentRepository paymentIntents = new LockingPaymentIntentRepository(intent);
        InMemoryRefundRepository refunds = new InMemoryRefundRepository();
        FakeProvider provider = new FakeProvider();
        provider.delayMs = 100;
        RefundService service = service(paymentIntents, refunds, provider);

        CountDownLatch start = new CountDownLatch(1);
        Callable<Result> task = () -> {
            start.await(5, TimeUnit.SECONDS);
            try {
                service.createRefund(MERCHANT_ID, INTENT_ID,
                        new CreateRefundRequest(7_000L, "requested_by_customer"));
                return new Result(true, null);
            } catch (Exception e) {
                return new Result(false, e);
            }
        };

        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Result> first = executor.submit(task);
            Future<Result> second = executor.submit(task);
            start.countDown();

            List<Result> results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));

            assertThat(results).extracting(Result::success).containsExactlyInAnyOrder(true, false);
            assertThat(results.stream().filter(result -> !result.success()).findFirst().orElseThrow().error())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds available refundable amount")
                    .hasMessageContaining("available: 3000");
            assertThat(refunds.activeSum()).isEqualTo(7_000L);
            assertThat(provider.refundCalls).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createRefund_providerSucceeds_writesRefundSucceededOutboxEvent() {
        PaymentIntent intent = succeededIntent(10_000L);
        LockingPaymentIntentRepository paymentIntents = new LockingPaymentIntentRepository(intent);
        InMemoryRefundRepository refunds = new InMemoryRefundRepository();
        InMemoryOutboxEventRepository outbox = new InMemoryOutboxEventRepository();
        FakeProvider provider = new FakeProvider();
        RefundService service = service(paymentIntents, refunds, outbox, provider);

        var response = service.createRefund(
                MERCHANT_ID, INTENT_ID, new CreateRefundRequest(4_000L, "requested_by_customer"));

        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(outbox.saved).hasSize(1);
        OutboxEvent event = outbox.saved.get(0);
        assertThat(event.getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(event.getEventType()).isEqualTo("refund.succeeded");
        assertThat(event.getResourceId()).isEqualTo(response.id());
        assertThat(event.getPayload()).contains("\"id\":\"" + response.id() + "\"");
        assertThat(event.getPayload()).contains("\"paymentIntentId\":\"" + INTENT_ID + "\"");
    }

    @Test
    void createRefund_providerFails_writesRefundFailedOutboxEvent() {
        PaymentIntent intent = succeededIntent(10_000L);
        LockingPaymentIntentRepository paymentIntents = new LockingPaymentIntentRepository(intent);
        InMemoryRefundRepository refunds = new InMemoryRefundRepository();
        InMemoryOutboxEventRepository outbox = new InMemoryOutboxEventRepository();
        FakeProvider provider = new FakeProvider();
        provider.success = false;
        RefundService service = service(paymentIntents, refunds, outbox, provider);

        var response = service.createRefund(
                MERCHANT_ID, INTENT_ID, new CreateRefundRequest(4_000L, "requested_by_customer"));

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.failureReason()).isEqualTo("provider declined refund");
        assertThat(outbox.saved).hasSize(1);
        OutboxEvent event = outbox.saved.get(0);
        assertThat(event.getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(event.getEventType()).isEqualTo("refund.failed");
        assertThat(event.getResourceId()).isEqualTo(response.id());
        assertThat(event.getPayload()).contains("\"failureReason\":\"provider declined refund\"");
    }

    private RefundService service(LockingPaymentIntentRepository paymentIntents,
                                  InMemoryRefundRepository refunds,
                                  FakeProvider provider) {
        return new RefundService(
                paymentIntents.proxy(),
                refunds.proxy(),
                new PaymentProviderDispatcher(List.of(provider)),
                new FakeProviderAccountService(),
                new InMemoryOutboxEventRepository().proxy(),
                new ObjectMapper().findAndRegisterModules(),
                new LockingTransactionManager(paymentIntents),
                new PaymentMetrics(new SimpleMeterRegistry()));
    }

    private RefundService service(LockingPaymentIntentRepository paymentIntents,
                                  InMemoryRefundRepository refunds,
                                  InMemoryOutboxEventRepository outbox,
                                  FakeProvider provider) {
        return new RefundService(
                paymentIntents.proxy(),
                refunds.proxy(),
                new PaymentProviderDispatcher(List.of(provider)),
                new FakeProviderAccountService(),
                outbox.proxy(),
                new ObjectMapper().findAndRegisterModules(),
                new LockingTransactionManager(paymentIntents),
                new PaymentMetrics(new SimpleMeterRegistry()));
    }

    private static PaymentIntent succeededIntent(long amount) {
        PaymentIntent intent = new PaymentIntent();
        intent.assignId(INTENT_ID);
        intent.setMerchantId(MERCHANT_ID);
        intent.setMode(ApiKeyMode.TEST);
        intent.setAmount(amount);
        intent.setCurrency("USD");
        intent.setIdempotencyKey("idem");
        intent.setCaptureMethod(CaptureMethod.AUTOMATIC);
        intent.setStatus(PaymentIntentStatus.SUCCEEDED);
        intent.setResolvedProvider(PaymentProvider.STRIPE);
        intent.setProviderPaymentId("pi_provider");
        return intent;
    }

    private record Result(boolean success, Exception error) {}

    private static final class LockingTransactionManager implements PlatformTransactionManager {
        private final LockingPaymentIntentRepository paymentIntents;

        private LockingTransactionManager(LockingPaymentIntentRepository paymentIntents) {
            this.paymentIntents = paymentIntents;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            paymentIntents.unlockIfHeld();
        }

        @Override
        public void rollback(TransactionStatus status) {
            paymentIntents.unlockIfHeld();
        }
    }

    private static final class LockingPaymentIntentRepository implements InvocationHandler {
        private final PaymentIntent intent;
        private final ReentrantLock lock = new ReentrantLock();
        private final ThreadLocal<Boolean> locked = ThreadLocal.withInitial(() -> false);

        private LockingPaymentIntentRepository(PaymentIntent intent) {
            this.intent = intent;
        }

        private PaymentIntentRepository proxy() {
            return (PaymentIntentRepository) Proxy.newProxyInstance(
                    PaymentIntentRepository.class.getClassLoader(),
                    new Class<?>[]{PaymentIntentRepository.class},
                    this);
        }

        private void unlockIfHeld() {
            if (locked.get()) {
                locked.set(false);
                lock.unlock();
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "findByIdAndMerchantIdForUpdate" -> {
                    lock.lock();
                    locked.set(true);
                    yield Optional.of(intent);
                }
                default -> defaultObjectMethod(this, method, args);
            };
        }
    }

    private static final class InMemoryRefundRepository implements InvocationHandler {
        private final List<Refund> saved = new ArrayList<>();

        private RefundRepository proxy() {
            return (RefundRepository) Proxy.newProxyInstance(
                    RefundRepository.class.getClassLoader(),
                    new Class<?>[]{RefundRepository.class},
                    this);
        }

        private void insertPending(UUID intentId, UUID merchantId, long amount) {
            Refund refund = new Refund();
            ReflectionTestUtils.setField(refund, "id", UUID.randomUUID());
            refund.setPaymentIntentId(intentId);
            refund.setMerchantId(merchantId);
            refund.setMode(ApiKeyMode.TEST);
            refund.setAmount(amount);
            refund.setCurrency("USD");
            refund.setStatus(RefundStatus.PENDING);
            saved.add(refund);
        }

        private long activeSum() {
            return saved.stream()
                    .filter(refund -> refund.getStatus() == RefundStatus.PENDING
                            || refund.getStatus() == RefundStatus.SUCCEEDED)
                    .mapToLong(Refund::getAmount)
                    .sum();
        }

        @Override
        public synchronized Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "sumActiveByPaymentIntentId" -> activeSum();
                case "save" -> {
                    Refund refund = (Refund) args[0];
                    if (refund.getId() == null) {
                        ReflectionTestUtils.setField(refund, "id", UUID.randomUUID());
                        saved.add(refund);
                    }
                    yield refund;
                }
                case "findById" -> saved.stream()
                        .filter(refund -> refund.getId().equals(args[0]))
                        .findFirst();
                default -> defaultObjectMethod(this, method, args);
            };
        }
    }

    private static final class InMemoryOutboxEventRepository implements InvocationHandler {
        private final List<OutboxEvent> saved = new ArrayList<>();

        private OutboxEventRepository proxy() {
            return (OutboxEventRepository) Proxy.newProxyInstance(
                    OutboxEventRepository.class.getClassLoader(),
                    new Class<?>[]{OutboxEventRepository.class},
                    this);
        }

        @Override
        public synchronized Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "save" -> {
                    OutboxEvent event = (OutboxEvent) args[0];
                    ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
                    saved.add(event);
                    yield event;
                }
                default -> defaultObjectMethod(this, method, args);
            };
        }
    }

    private static final class FakeProvider extends AbstractFakeProvider {
        private int refundCalls;
        private long delayMs;
        private boolean success = true;

        @Override
        public RefundResult refund(RefundRequest request, ProviderCredentials creds) {
            refundCalls++;
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new RefundResult(success, success ? "re_" + request.refundId() : null,
                    success ? null : "provider declined refund");
        }
    }

    private abstract static class AbstractFakeProvider implements PaymentProviderService {
        @Override
        public PaymentProvider brand() {
            return PaymentProvider.STRIPE;
        }

        @Override
        public ChargeResult charge(ChargeRequest request, ProviderCredentials creds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<PaymentIntentStatus> syncStatus(String providerPaymentId,
                                                                  ProviderCredentials creds) {
            return java.util.Optional.empty();
        }

        @Override
        public boolean cancelAtProvider(String providerPaymentId, ProviderCredentials creds) {
            return false;
        }

        @Override
        public boolean captureAtProvider(String providerPaymentId, ProviderCredentials creds) {
            return false;
        }
    }

    private static final class FakeProviderAccountService extends ProviderAccountService {
        private FakeProviderAccountService() {
            super(null, null, null, new com.masonx.paygateway.provider.simulator.ProviderSimulatorProperties());
        }

        @Override
        public ProviderCredentials resolveCredentials(UUID merchantId, PaymentProvider provider, ApiKeyMode mode) {
            return new StripeCredentials("sk_test", "pk_test");
        }
    }

    private static Object defaultObjectMethod(Object target, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> target.getClass().getSimpleName();
            case "hashCode" -> System.identityHashCode(target);
            case "equals" -> target == args[0];
            default -> throw new UnsupportedOperationException(method.toString());
        };
    }
}
