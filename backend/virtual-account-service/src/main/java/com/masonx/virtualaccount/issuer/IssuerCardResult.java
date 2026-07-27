package com.masonx.virtualaccount.issuer;

public record IssuerCardResult(
        String externalIssuerCardId,
        IssuerCardStatus status
) {
}
