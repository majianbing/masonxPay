package com.masonx.paygateway.health;

import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2.3 — Enhanced actuator health.
 *
 * Exposed at GET /actuator/health/connectors (Spring Boot aggregates it automatically).
 *
 * Reports how many ACTIVE connector accounts exist per provider — a lightweight DB read
 * that gives load balancers and ops a view of connector availability without pinging
 * external providers (which would incorrectly mark the service as unhealthy when a
 * provider has a transient outage but payments would still reroute via fallback).
 *
 * Status logic:
 *   UP    — at least one ACTIVE connector account exists across all providers
 *   DOWN  — zero ACTIVE connectors (no payment method can be processed)
 */
@Component("connectors")
public class ConnectorHealthIndicator implements HealthIndicator {

    private final ProviderAccountRepository providerAccountRepository;

    public ConnectorHealthIndicator(ProviderAccountRepository providerAccountRepository) {
        this.providerAccountRepository = providerAccountRepository;
    }

    @Override
    public Health health() {
        try {
            List<ProviderAccount> active = providerAccountRepository.findAllByStatus(ProviderAccountStatus.ACTIVE);

            // Count per provider — preserve insertion order so the response is stable
            Map<String, Long> countByProvider = new LinkedHashMap<>();
            for (PaymentProvider p : PaymentProvider.values()) {
                countByProvider.put(p.name(), 0L);
            }
            for (ProviderAccount account : active) {
                countByProvider.merge(account.getProvider().name(), 1L, Long::sum);
            }

            Health.Builder builder = active.isEmpty() ? Health.down() : Health.up();
            builder.withDetail("activeConnectors", active.size());
            countByProvider.forEach(builder::withDetail);
            return builder.build();

        } catch (Exception ex) {
            return Health.down(ex).build();
        }
    }
}
