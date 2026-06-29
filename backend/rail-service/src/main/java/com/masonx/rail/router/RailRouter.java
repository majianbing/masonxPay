package com.masonx.rail.router;

import com.masonx.rail.canonical.CanonicalPaymentCommand;
import com.masonx.rail.canonical.PaymentRailAdapter;
import com.masonx.rail.canonical.RailResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Selects the appropriate {@link PaymentRailAdapter} for a command, persists the
 * routing decision, and delegates execution.
 *
 * <p>MR0: routing decision persistence is in place; adapter delegation throws
 * {@link UnsupportedOperationException} until MR1 wires real adapters.
 */
@Component
public class RailRouter {

    private static final Logger log = LoggerFactory.getLogger(RailRouter.class);

    private final List<PaymentRailAdapter> adapters;
    private final JdbcTemplate jdbc;

    public RailRouter(List<PaymentRailAdapter> adapters, JdbcTemplate jdbc) {
        this.adapters = adapters;
        this.jdbc = jdbc;
    }

    public RailResponse route(CanonicalPaymentCommand command) {
        var adapter = adapters.stream()
                .filter(a -> a.supports(command.rail(), command.metadata().getOrDefault("network", "")))
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException(
                        "No adapter registered for rail=%s — coming in MR1".formatted(command.rail())));

        var decision = new RailRoutingDecision(
                "rd_" + UUID.randomUUID().toString().replace("-", ""),
                command.paymentId(),
                command.rail(),
                command.metadata().getOrDefault("network", "UNKNOWN"),
                adapter.getClass().getSimpleName(),
                Instant.now()
        );
        persistDecision(decision);
        log.info("Rail routing decision persisted paymentId={} rail={} adapter={}",
                command.paymentId(), command.rail(), decision.adapterClass());

        return adapter.execute(command);
    }

    private void persistDecision(RailRoutingDecision d) {
        jdbc.update("""
                INSERT INTO rail_routing_decision (id, payment_id, rail, network, adapter, decided_at)
                VALUES (?, ?, ?::rail_type, ?, ?, ?)
                """,
                d.id(), d.paymentId(), d.rail().name(),
                d.network(), d.adapterClass(), d.decidedAt());
    }
}
