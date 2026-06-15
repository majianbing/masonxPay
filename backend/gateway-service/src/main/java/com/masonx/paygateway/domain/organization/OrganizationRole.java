package com.masonx.paygateway.domain.organization;

public enum OrganizationRole {
    /** Full org control — create/delete merchants, manage org members */
    ORG_OWNER,
    /** Manage merchants and team, no org deletion */
    ORG_ADMIN,
    /** Access governed by individual merchant-level roles */
    ORG_MEMBER
}
