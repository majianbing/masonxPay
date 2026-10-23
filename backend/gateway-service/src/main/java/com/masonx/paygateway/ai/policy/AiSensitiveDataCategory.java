package com.masonx.paygateway.ai.policy;

/**
 * Data classes that must never reach a model provider, a vector index, or an AI
 * audit record, in any deployment mode. Redaction is enforced in code before the
 * Java-to-AI-service boundary; this catalog is what redactors and tests assert against.
 */
public enum AiSensitiveDataCategory {
    PAN,
    CVV,
    TRACK_DATA,
    PROVIDER_CREDENTIAL,
    API_KEY,
    WEBHOOK_SECRET,
    SIGNATURE_HEADER,
    PRIVATE_KEY,
    ACCESS_TOKEN,
    REFRESH_TOKEN,
    MFA_SECRET,
    RAW_PROVIDER_PAYLOAD,
    RAW_WEBHOOK_BODY,
    RAW_DATABASE_ROW,
    UNREDACTED_CUSTOMER_PII,
    FULL_CUSTOMER_PAYMENT_PAYLOAD,
    CROSS_TENANT_DATA
}
