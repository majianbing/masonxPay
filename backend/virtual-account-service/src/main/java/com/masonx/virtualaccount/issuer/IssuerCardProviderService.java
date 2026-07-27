package com.masonx.virtualaccount.issuer;

import com.masonx.virtualaccount.domain.constant.IssuerPartnerType;

public interface IssuerCardProviderService {

    IssuerPartnerType provider();

    CreateIssuerCardResult createCard(CreateIssuerCardCommand command);

    IssuerCardResult lockCard(String externalCardId, String reason, String idempotencyKey);

    IssuerCardResult unlockCard(String externalCardId, String idempotencyKey);

    IssuerCardResult terminateCard(String externalCardId, String reason, String idempotencyKey);
}
