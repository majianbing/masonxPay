package com.masonx.virtualaccount.issuer;

import java.time.LocalDate;

public record CreateIssuerCardResult(
        String externalIssuerCardId,
        String externalCardToken,
        String cardTokenId,
        String maskedPan,
        String bin,
        String oneTimeTestPan,
        LocalDate expiry,
        IssuerCardStatus status
) {
}
