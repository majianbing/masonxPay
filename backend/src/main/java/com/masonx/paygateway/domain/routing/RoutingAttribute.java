package com.masonx.paygateway.domain.routing;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "routing_attributes")
public class RoutingAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "attribute_key", nullable = false, length = 100)
    private String key;

    @Column(nullable = false, length = 120)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RoutingAttributeType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RoutingAttributeSource source;

    @Column(name = "allowed_operators", nullable = false, columnDefinition = "TEXT")
    private String allowedOperators;

    @Column(name = "enum_values", columnDefinition = "TEXT")
    private String enumValues;

    @Column(name = "required_before_routing", nullable = false)
    private boolean requiredBeforeRouting = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "pii_classification", nullable = false, length = 30)
    private RoutingAttributePiiClassification piiClassification = RoutingAttributePiiClassification.NONE;

    @Column(name = "max_value_length", nullable = false)
    private int maxValueLength = 255;

    @Column(nullable = false)
    private boolean enabled = true;

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
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public RoutingAttributeType getType() { return type; }
    public void setType(RoutingAttributeType type) { this.type = type; }
    public RoutingAttributeSource getSource() { return source; }
    public void setSource(RoutingAttributeSource source) { this.source = source; }
    public String getAllowedOperators() { return allowedOperators; }
    public void setAllowedOperators(String allowedOperators) { this.allowedOperators = allowedOperators; }
    public String getEnumValues() { return enumValues; }
    public void setEnumValues(String enumValues) { this.enumValues = enumValues; }
    public boolean isRequiredBeforeRouting() { return requiredBeforeRouting; }
    public void setRequiredBeforeRouting(boolean requiredBeforeRouting) { this.requiredBeforeRouting = requiredBeforeRouting; }
    public RoutingAttributePiiClassification getPiiClassification() { return piiClassification; }
    public void setPiiClassification(RoutingAttributePiiClassification piiClassification) {
        this.piiClassification = piiClassification;
    }
    public int getMaxValueLength() { return maxValueLength; }
    public void setMaxValueLength(int maxValueLength) { this.maxValueLength = maxValueLength; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
