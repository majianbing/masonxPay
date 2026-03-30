package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.routing.RoutingRule;
import com.masonx.paygateway.domain.routing.RoutingRuleRepository;
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

    @Mock RoutingRuleRepository routingRuleRepository;
    @Mock ProviderAccountRepository providerAccountRepository;

    private RoutingEngine engine;
    private final UUID merchantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        engine = new RoutingEngine(routingRuleRepository, providerAccountRepository);
    }

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
        ReflectionTestUtils.setField(acc, "status", ProviderAccountStatus.ACTIVE);
        ReflectionTestUtils.setField(acc, "primary", primary);
        ReflectionTestUtils.setField(acc, "provider", PaymentProvider.STRIPE);
        ReflectionTestUtils.setField(acc, "weight", 1);
        return acc;
    }

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
        UUID primaryId = UUID.randomUUID();
        UUID fallbackId = UUID.randomUUID();
        RoutingRule rule = ruleFor(primaryId);
        rule.setFallbackAccountId(fallbackId);

        ProviderAccount primary = account(primaryId, true);
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
    void resolveAnyAccount_prefersPrimaryFlaggedAccount() {
        ProviderAccount nonPrimary = account(UUID.randomUUID(), false);
        ProviderAccount primary = account(UUID.randomUUID(), true);

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
    void resolve_paymentMethodTypeMismatch_returnsEmpty() {
        RoutingRule rule = ruleFor(UUID.randomUUID());
        rule.setPaymentMethodTypeList(List.of("sepa_debit"));

        when(routingRuleRepository.findByMerchantIdAndEnabledTrueOrderByPriorityAsc(merchantId))
                .thenReturn(List.of(rule));

        assertThat(engine.resolve(merchantId, 1000, "USD", null, "card")).isEmpty();
    }
}
