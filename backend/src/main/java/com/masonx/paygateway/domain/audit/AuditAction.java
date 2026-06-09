package com.masonx.paygateway.domain.audit;

public enum AuditAction {
    CONNECTOR_CREATED,
    CONNECTOR_UPDATED,
    CONNECTOR_DELETED,
    REFUND_ISSUED,
    MEMBER_INVITED,
    MEMBER_ROLE_CHANGED,
    MEMBER_REVOKED,
    API_KEY_CREATED,
    API_KEY_REVOKED
}
