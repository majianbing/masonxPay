package com.masonx.paygateway.provider;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.credentials.SimulatorCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PaymentProviderDispatcherTest {

    private PaymentProviderService mockService;
    private PaymentProviderDispatcher dispatcher;
    private ListAppender<ILoggingEvent> logAppender;
    private ch.qos.logback.classic.Logger dispatcherLogger;

    private final SimulatorCredentials creds = new SimulatorCredentials(true, 1.0);

    @BeforeEach
    void setUp() {
        mockService = mock(PaymentProviderService.class);
        when(mockService.brand()).thenReturn(PaymentProvider.SIMULATOR);
        dispatcher = new PaymentProviderDispatcher(List.of(mockService));

        dispatcherLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(PaymentProviderDispatcher.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        dispatcherLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        dispatcherLogger.detachAppender(logAppender);
    }

    // ── charge ────────────────────────────────────────────────────────────────

    @Test
    void charge_success_logsStartAndEndAtInfo() {
        ChargeRequest req = chargeRequest();
        ChargeResult result = new ChargeResult(true, "pi_123", "{}", null, null, false, false, false, null, null, null);
        when(mockService.charge(req, creds)).thenReturn(result);

        dispatcher.charge(PaymentProvider.SIMULATOR, req, creds);

        List<ILoggingEvent> logs = logAppender.list;
        assertThat(logs).hasSize(2);

        ILoggingEvent start = logs.get(0);
        assertThat(start.getLevel()).isEqualTo(Level.INFO);
        assertThat(start.getFormattedMessage()).contains("provider.call.start", "op=CHARGE", "SIMULATOR");
        assertThat(start.getFormattedMessage()).contains(req.paymentIntentId().toString());
        assertThat(start.getFormattedMessage()).contains("amount=1000", "currency=usd");

        ILoggingEvent end = logs.get(1);
        assertThat(end.getLevel()).isEqualTo(Level.INFO);
        assertThat(end.getFormattedMessage()).contains("provider.call.end", "op=CHARGE", "success=true", "pi_123");
        assertThat(end.getFormattedMessage()).contains("durationMs=");
    }

    @Test
    void charge_softFailure_logsEndAtWarn() {
        ChargeRequest req = chargeRequest();
        ChargeResult failed = new ChargeResult(false, null, "{}", "card_declined", "Your card was declined", false, false, false, null, null, null);
        when(mockService.charge(req, creds)).thenReturn(failed);

        dispatcher.charge(PaymentProvider.SIMULATOR, req, creds);

        List<ILoggingEvent> logs = logAppender.list;
        assertThat(logs.get(0).getLevel()).isEqualTo(Level.INFO);  // start always INFO
        assertThat(logs.get(1).getLevel()).isEqualTo(Level.WARN);
        assertThat(logs.get(1).getFormattedMessage()).contains("success=false", "card_declined");
    }

    @Test
    void charge_requiresAction_logsEndAtInfo() {
        ChargeRequest req = chargeRequest();
        ChargeResult action = ChargeResult.actionRequired("pi_456", "{}", "redirect_url", "https://bank.example/3ds", null);
        when(mockService.charge(req, creds)).thenReturn(action);

        dispatcher.charge(PaymentProvider.SIMULATOR, req, creds);

        assertThat(logAppender.list.get(1).getLevel()).isEqualTo(Level.INFO);
        assertThat(logAppender.list.get(1).getFormattedMessage()).contains("requiresAction=true");
    }

    @Test
    void charge_exception_logsErrorAndRethrows() {
        ChargeRequest req = chargeRequest();
        RuntimeException boom = new RuntimeException("network timeout");
        when(mockService.charge(req, creds)).thenThrow(boom);

        assertThatThrownBy(() -> dispatcher.charge(PaymentProvider.SIMULATOR, req, creds))
                .isSameAs(boom);

        List<ILoggingEvent> logs = logAppender.list;
        assertThat(logs).hasSize(2);
        assertThat(logs.get(1).getLevel()).isEqualTo(Level.ERROR);
        assertThat(logs.get(1).getFormattedMessage()).contains("provider.call.error", "network timeout");
    }

    // ── refund ────────────────────────────────────────────────────────────────

    @Test
    void refund_success_logsStartAndEndAtInfo() {
        RefundRequest req = new RefundRequest(UUID.randomUUID(), "pi_123", 500, "customer_request");
        RefundResult result = new RefundResult(true, "re_789", null);
        when(mockService.refund(req, creds)).thenReturn(result);

        dispatcher.refund(PaymentProvider.SIMULATOR, req, creds);

        assertThat(logAppender.list).hasSize(2);
        assertThat(logAppender.list.get(0).getFormattedMessage()).contains("op=REFUND", req.refundId().toString());
        assertThat(logAppender.list.get(1).getLevel()).isEqualTo(Level.INFO);
        assertThat(logAppender.list.get(1).getFormattedMessage()).contains("providerRefundId=re_789");
    }

    @Test
    void refund_failure_logsWarn() {
        RefundRequest req = new RefundRequest(UUID.randomUUID(), "pi_123", 500, null);
        when(mockService.refund(req, creds)).thenReturn(new RefundResult(false, null, "already_refunded"));

        dispatcher.refund(PaymentProvider.SIMULATOR, req, creds);

        assertThat(logAppender.list.get(1).getLevel()).isEqualTo(Level.WARN);
        assertThat(logAppender.list.get(1).getFormattedMessage()).contains("already_refunded");
    }

    // ── syncStatus ────────────────────────────────────────────────────────────

    @Test
    void syncStatus_resolved_logsStatus() {
        when(mockService.syncStatus("pi_abc", creds)).thenReturn(Optional.of(PaymentIntentStatus.SUCCEEDED));

        dispatcher.syncStatus(PaymentProvider.SIMULATOR, "pi_abc", creds);

        assertThat(logAppender.list).hasSize(2);
        assertThat(logAppender.list.get(1).getFormattedMessage()).contains("SUCCEEDED");
    }

    @Test
    void syncStatus_inFlight_logsInFlight() {
        when(mockService.syncStatus("pi_abc", creds)).thenReturn(Optional.empty());

        dispatcher.syncStatus(PaymentProvider.SIMULATOR, "pi_abc", creds);

        assertThat(logAppender.list.get(1).getFormattedMessage()).contains("in-flight");
    }

    // ── capture / cancel ──────────────────────────────────────────────────────

    @Test
    void captureAtProvider_logsAccepted() {
        when(mockService.captureAtProvider("pi_cap", creds)).thenReturn(true);

        dispatcher.captureAtProvider(PaymentProvider.SIMULATOR, "pi_cap", creds);

        assertThat(logAppender.list.get(0).getFormattedMessage()).contains("op=CAPTURE");
        assertThat(logAppender.list.get(1).getFormattedMessage()).contains("accepted=true");
    }

    @Test
    void cancelAtProvider_logsAccepted() {
        when(mockService.cancelAtProvider("pi_can", creds)).thenReturn(false);

        dispatcher.cancelAtProvider(PaymentProvider.SIMULATOR, "pi_can", creds);

        assertThat(logAppender.list.get(0).getFormattedMessage()).contains("op=CANCEL");
        assertThat(logAppender.list.get(1).getFormattedMessage()).contains("accepted=false");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ChargeRequest chargeRequest() {
        return new ChargeRequest(
                UUID.randomUUID(), 1000, "usd", "card",
                null,   // paymentMethodId — not logged
                null,   // providerCustomerReference — not logged
                "idem-key-1", null, null, CaptureMethod.AUTOMATIC, null);
    }
}
