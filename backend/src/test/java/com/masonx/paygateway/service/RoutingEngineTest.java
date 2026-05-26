package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.routing.RoutingRule;
import com.masonx.paygateway.domain.routing.RoutingRuleRepository;
import com.masonx.paygateway.health.ConnectorHealthService;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import com.masonx.paygateway.service.routing.ProviderAccountCapabilityService;
import com.masonx.paygateway.service.routing.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoutingEngineTest {

    @Mock RoutingRuleRepository      routingRuleRepository;
    @Mock ProviderAccountRepository  providerAccountRepository;
    @Mock ConnectorCircuitBreaker    circuitBreaker;
    @Mock ConnectorHealthService     healthService;
    @Mock ProviderAccountCapabilityService capabilityService;

    private RoutingEngine engine;
    private final UUID merchantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ConnectorFeeService feeService = new ConnectorFeeService();
        engine = new RoutingEngine(routingRuleRepository, providerAccountRepository,
                circuitBreaker, healthService, feeService);
        // Default: all circuits closed, all connectors healthy — safe baseline for existing tests
        lenient().when(circuitBreaker.isOpen(any())).thenReturn(false);
        lenient().when(healthService.getSuccessRate(any())).thenReturn(1.0);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RoutingRule ruleFor(UUID accountId) {
        RoutingRule rule = new RoutingRule();
        rule.setMerchantId(merchantId);
        rule.setEnabled(true);
        rule.setPriority(1);
        rule.setTargetAccountId(accountId);
        return rule;
    }

    private ProviderAccount account(UUID id, boolean primary) {
        ProviderAccount acc = new ProviderAccount();
        ReflectionTestUtils.setField(acc, "id", id);
        ReflectionTestUtils.setField(acc, "merchantId", merchantId);
        ReflectionTestUtils.setField(acc, "status", ProviderAccountStatus.ACTIVE);
        ReflectionTestUtils.setField(acc, "primary", primary);
        ReflectionTestUtils.setField(acc, "provider", PaymentProvider.STRIPE);
        ReflectionTestUtils.setField(acc, "weight", 1);
        return acc;
    }

    private RoutingContext context(String method, String currency) {
        return new RoutingContext(merchantId, ApiKeyMode.TEST, 1000, currency, null, method,
                CaptureMethod.AUTOMATIC, null, null, java.util.Map.of(), null, null, null,
                null, null, null, null, null);
    }

    // ── basic routing (unchanged behaviour) ──────────────────────────────────

    @Test
    void resolve_noRules_returnsEmpty() {
        when(routingRuleRepository.findByMerchantIdAndEnabledTrueOrderByPriorityAsc(merchantId))
                .thenReturn(List.of());

        assertThat(engine.resolve(merchantId, 1000, "USD", null, "card")).isEmpty();
    }

    @Test
    void resolve_currencyMismatch_returnsEmpty() {
        RoutingRule rule = ruleFor(UUID.randomUUID());
        rule.setCurrencyList(List.of("EUR"));

        when(routingRuleRepository.findByMerchantIdAndEnabledTrueOrderByPriorityAsc(merchantId))
                .thenReturn(List.of(rule));

        assertThat(engine.resolve(merchantId, 1000, "USD", null, "card")).isEmpty();
    }

    @Test
    void resolve_amountBelowMin_returnsEmpty() {
        RoutingRule rule = ruleFor(UUID.randomUUID());
        rule.setAmountMin(5000L);

        when(routingRuleRepository.findByMerchantIdAndEnabledTrueOrderByPriorityAsc(merchantId))
                .thenReturn(List.of(rule));

        assertThat(engine.resolve(merchantId, 100, "USD", null, "card")).isEmpty();
    }

    @Test
    void resolve_amountAboveMax_returnsEmpty() {
        RoutingRule rule = ruleFor(UUID.randomUUID());
        rule.setAmountMax(500L);

        when(routingRuleRepository.findByMerchantIdAndEnabledTrueOrderByPriorityAsc(merchantId))
                .thenReturn(List.of(rule));

        assertThat(engine.resolve(merchantId, 1000, "USD", null, "card")).isEmpty();
    }

    @Test
    void resolve_matchingRule_returnsPrimaryAccount() {
        UUID accountId = UUID.randomUUID();
        RoutingRule rule = ruleFor(accountId);
        rule.setCurrencyList(List.of("USD"));

        ProviderAccount acc = account(accountId, true);

        when(routingRuleRepository.findByMerchantIdAndEnabledTrueOrderByPriorityAsc(merchantId))
                .thenReturn(List.of(rule));
        when(providerAccountRepository.findById(accountId)).thenReturn(Optional.of(acc));

        Optional<RoutingEngine.RoutingResult> result =
                engine.resolve(merchantId, 1000, "USD", null, "card");

        assertThat(result).isPresent();
        assertThat(result.get().primary()).isEqualTo(acc);
        assertThat(result.get().hasFallback()).isFalse();
    }

    @Test
    void resolve_matchingRuleWithFallback_returnsBothAccounts() {
        UUID primaryId  = UUID.randomUUID();
        UUID fallbackId = UUID.randomUUID();
        RoutingRule rule = ruleFor(primaryId);
        rule.setFallbackAccountId(fallbackId);

        ProviderAccount primary  = account(primaryId, true);
        ProviderAccount fallback = account(fallbackId, false);

        when(routingRuleRepository.findByMerchantIdAndEnabledTrueOrderByPriorityAsc(merchantId))
                .thenReturn(List.of(rule));
        when(providerAccountRepository.findById(primaryId)).thenReturn(Optional.of(primary));
        when(providerAccountRepository.findById(fallbackId)).thenReturn(Optional.of(fallback));

        RoutingEngine.RoutingResult result =
                engine.resolve(merchantId, 1000, "USD", null, "card").orElseThrow();

        assertThat(result.primary()).isEqualTo(primary);
        assertThat(result.fallback()).isEqualTo(fallback);
        assertThat(result.hasFallback()).isTrue();
    }

    @Test
    void resolve_paymentMethodTypeMismatch_returnsEmpty() {
        RoutingRule rule = ruleFor(UUID.randomUUID());
        rule.setPaymentMethodTypeList(List.of("sepa_debit"));

        when(routingRuleRepository.findByMerchantIdAndEnabledTrueOrderByPriorityAsc(merchantId))
                .thenReturn(List.of(rule));

        assertThat(engine.resolve(merchantId, 1000, "USD", null, "card")).isEmpty();
    }

    @Test
    void resolveAnyAccount_prefersPrimaryFlaggedAccount() {
        ProviderAccount nonPrimary = account(UUID.randomUUID(), false);
        ProviderAccount primary    = account(UUID.randomUUID(), true);

        when(providerAccountRepository.findAllByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, ApiKeyMode.TEST))
                .thenReturn(List.of(nonPrimary, primary));

        Optional<ProviderAccount> result = engine.resolveAnyAccount(merchantId, ApiKeyMode.TEST);
        assertThat(result).contains(primary);
    }

    @Test
    void resolveAnyAccount_noAccounts_returnsEmpty() {
        when(providerAccountRepository.findAllByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, ApiKeyMode.TEST))
                .thenReturn(List.of());

        assertThat(engine.resolveAnyAccount(merchantId, ApiKeyMode.TEST)).isEmpty();
    }

    @Test
    void resolveAccountForProvider_withContext_filtersByCapabilities() {
        RoutingEngine capabilityAware = new RoutingEngine(
                routingRuleRepository,
                providerAccountRepository,
                circuitBreaker,
                healthService,
                new ConnectorFeeService(),
                null,
                null,
                null,
                capabilityService,
                null);

        ProviderAccount unsupported = account(UUID.randomUUID(), true);
        ProviderAccount supported = account(UUID.randomUUID(), false);

        when(providerAccountRepository.findAllByMerchantIdAndProviderAndModeAndStatus(
                merchantId, PaymentProvider.STRIPE, ApiKeyMode.TEST, ProviderAccountStatus.ACTIVE))
                .thenReturn(List.of(unsupported, supported));
        when(capabilityService.supportsOrUnspecified(merchantId, unsupported.getId(), context("card", "USD")))
                .thenReturn(false);
        when(capabilityService.supportsOrUnspecified(merchantId, supported.getId(), context("card", "USD")))
                .thenReturn(true);

        Optional<ProviderAccount> result = capabilityAware.resolveAccountForProvider(
                merchantId, PaymentProvider.STRIPE, ApiKeyMode.TEST, context("card", "USD"));

        assertThat(result).contains(supported);
    }

    // ── Phase 3.2: circuit breaker ────────────────────────────────────────────

    @Test
    void resolve_primaryCircuitOpen_fallbackAvailableAndHealthy_promotesFallback() {
        UUID primaryId  = UUID.randomUUID();
        UUID fallbackId = UUID.randomUUID();

        RoutingRule rule = ruleFor(primaryId);
        rule.setFallbackAccountId(fallbackId);

        ProviderAccount primary  = account(primaryId, true);
        ProviderAccount fallback = account(fallbackId, false);

        when(routingRuleRepository.findByMerchantIdAndEnabledTrueOrderByPriorityAsc(merchantId))
                .thenReturn(List.of(rule));
        when(providerAccountRepository.findById(primaryId)).thenReturn(Optional.of(primary));
        when(providerAccountRepository.findById(fallbackId)).thenReturn(Optional.of(fallback));

        // Primary circuit open, fallback closed
        when(circuitBreaker.isOpen(primaryId)).thenReturn(true);
        when(circuitBreaker.isOpen(fallbackId)).thenReturn(false);

        RoutingEngine.RoutingResult result =
                engine.resolve(merchantId, 1000, "USD", null, "card").orElseThrow();

        // Fallback should be promoted to primary; no further fallback available
        assertThat(result.primary()).isEqualTo(fallback);
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    void resolve_primaryCircuitOpen_noFallback_stillReturnsPrimary() {
        // If primary is open but there's no fallback, we must still use it — no other option.
        UUID primaryId = UUID.randomUUID();
        RoutingRule rule = ruleFor(primaryId);
        ProviderAccount primary = account(primaryId, true);

        when(routingRuleRepository.findByMerchantIdAndEnabledTrueOrderByPriorityAsc(merchantId))
                .thenReturn(List.of(rule));
        when(providerAccountRepository.findById(primaryId)).thenReturn(Optional.of(primary));
        when(circuitBreaker.isOpen(primaryId)).thenReturn(true);

        RoutingEngine.RoutingResult result =
                engine.resolve(merchantId, 1000, "USD", null, "card").orElseThrow();

        assertThat(result.primary()).isEqualTo(primary);
    }

    @Test
    void resolveAnyAccount_filtersOpenCircuits_whenAlternativeExists() {
        UUID openId   = UUID.randomUUID();
        UUID closedId = UUID.randomUUID();

        ProviderAccount openAccount   = account(openId, true);   // primary flag but circuit open
        ProviderAccount closedAccount = account(closedId, false); // non-primary, circuit closed

        when(providerAccountRepository.findAllByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, ApiKeyMode.TEST))
                .thenReturn(List.of(openAccount, closedAccount));
        when(circuitBreaker.isOpen(openId)).thenReturn(true);
        when(circuitBreaker.isOpen(closedId)).thenReturn(false);

        // closedAccount should be preferred over openAccount even though openAccount is primary
        Optional<ProviderAccount> result = engine.resolveAnyAccount(merchantId, ApiKeyMode.TEST);
        assertThat(result).contains(closedAccount);
    }

    @Test
    void resolveAnyAccount_allCircuitsOpen_stillReturnsAnAccount() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        ProviderAccount acc1 = account(id1, true);
        ProviderAccount acc2 = account(id2, false);

        when(providerAccountRepository.findAllByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, ApiKeyMode.TEST))
                .thenReturn(List.of(acc1, acc2));
        when(circuitBreaker.isOpen(id1)).thenReturn(true);
        when(circuitBreaker.isOpen(id2)).thenReturn(true);

        // All circuits open — fall back to all accounts; primary-flagged acc1 is still preferred
        Optional<ProviderAccount> result = engine.resolveAnyAccount(merchantId, ApiKeyMode.TEST);
        assertThat(result).contains(acc1);
    }

    // ── Phase 3.1: success-rate routing ──────────────────────────────────────

    @Test
    void resolve_twoRules_prefersHealthyPrimaryOverDegraded() {
        UUID healthyAccountId  = UUID.randomUUID();
        UUID degradedAccountId = UUID.randomUUID();

        RoutingRule degradedRule = ruleFor(degradedAccountId);
        degradedRule.setPriority(1);

        RoutingRule healthyRule = ruleFor(healthyAccountId);
        healthyRule.setPriority(2);

        ProviderAccount healthyAcc  = account(healthyAccountId, false);
        ProviderAccount degradedAcc = account(degradedAccountId, true);

        when(routingRuleRepository.findByMerchantIdAndEnabledTrueOrderByPriorityAsc(merchantId))
                .thenReturn(List.of(degradedRule, healthyRule));
        when(providerAccountRepository.findById(healthyAccountId)).thenReturn(Optional.of(healthyAcc));
        // degradedAccountId.findById is never called — the degraded rule is excluded from the pool
        lenient().when(providerAccountRepository.findById(degradedAccountId)).thenReturn(Optional.of(degradedAcc));

        // Degraded rule's account has low success rate; healthy rule's account is above threshold
        when(healthService.getSuccessRate(degradedAccountId))
                .thenReturn(0.60); // below 80% threshold
        when(healthService.getSuccessRate(healthyAccountId))
                .thenReturn(0.95); // above threshold

        RoutingEngine.RoutingResult result =
                engine.resolve(merchantId, 1000, "USD", null, "card").orElseThrow();

        // Should pick the healthy rule's account, not the degraded one
        assertThat(result.primary()).isEqualTo(healthyAcc);
    }

    @Test
    void resolve_allRulesDegraded_stillRoutesToBestAvailable() {
        UUID accountId = UUID.randomUUID();
        RoutingRule rule = ruleFor(accountId);
        ProviderAccount acc = account(accountId, true);

        when(routingRuleRepository.findByMerchantIdAndEnabledTrueOrderByPriorityAsc(merchantId))
                .thenReturn(List.of(rule));
        when(providerAccountRepository.findById(accountId)).thenReturn(Optional.of(acc));
        when(healthService.getSuccessRate(accountId)).thenReturn(0.50); // degraded

        // Only degraded rule — should still route to it (no other choice)
        RoutingEngine.RoutingResult result =
                engine.resolve(merchantId, 1000, "USD", null, "card").orElseThrow();

        assertThat(result.primary()).isEqualTo(acc);
    }
}
