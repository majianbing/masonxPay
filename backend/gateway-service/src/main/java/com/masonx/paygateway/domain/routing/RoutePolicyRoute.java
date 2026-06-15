package com.masonx.paygateway.domain.routing;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "route_policy_routes")
public class RoutePolicyRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "route_order", nullable = false)
    private int routeOrder;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "default_route", nullable = false)
    private boolean defaultRoute = false;

    /** Canonical structured condition JSON. Empty object means match-all for the default route. */
    @Column(name = "conditions_json", nullable = false, columnDefinition = "TEXT")
    private String conditionsJson = "{}";

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
    public int getRouteOrder() { return routeOrder; }
    public void setRouteOrder(int routeOrder) { this.routeOrder = routeOrder; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isDefaultRoute() { return defaultRoute; }
    public void setDefaultRoute(boolean defaultRoute) { this.defaultRoute = defaultRoute; }
    public String getConditionsJson() { return conditionsJson; }
    public void setConditionsJson(String conditionsJson) { this.conditionsJson = conditionsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
