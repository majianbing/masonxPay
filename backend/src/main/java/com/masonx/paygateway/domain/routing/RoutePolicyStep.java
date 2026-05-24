package com.masonx.paygateway.domain.routing;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "route_policy_steps")
public class RoutePolicyStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "route_id", nullable = false)
    private UUID routeId;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(name = "provider_account_id", nullable = false)
    private UUID providerAccountId;

    @Column(name = "traffic_weight", nullable = false)
    private int trafficWeight = 100;

    @Column(name = "max_cost_bps")
    private Integer maxCostBps;

    @Column(name = "skip_if_degraded", nullable = false)
    private boolean skipIfDegraded = true;

    /** Canonical outcome-action JSON. Example: {"APPROVED":"finish","PROVIDER_TIMEOUT":"next"}. */
    @Column(name = "outcome_actions_json", nullable = false, columnDefinition = "TEXT")
    private String outcomeActionsJson = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public UUID getRouteId() { return routeId; }
    public void setRouteId(UUID routeId) { this.routeId = routeId; }
    public int getStepOrder() { return stepOrder; }
    public void setStepOrder(int stepOrder) { this.stepOrder = stepOrder; }
    public UUID getProviderAccountId() { return providerAccountId; }
    public void setProviderAccountId(UUID providerAccountId) { this.providerAccountId = providerAccountId; }
    public int getTrafficWeight() { return trafficWeight; }
    public void setTrafficWeight(int trafficWeight) { this.trafficWeight = trafficWeight; }
    public Integer getMaxCostBps() { return maxCostBps; }
    public void setMaxCostBps(Integer maxCostBps) { this.maxCostBps = maxCostBps; }
    public boolean isSkipIfDegraded() { return skipIfDegraded; }
    public void setSkipIfDegraded(boolean skipIfDegraded) { this.skipIfDegraded = skipIfDegraded; }
    public String getOutcomeActionsJson() { return outcomeActionsJson; }
    public void setOutcomeActionsJson(String outcomeActionsJson) { this.outcomeActionsJson = outcomeActionsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
