package com.masonx.virtualaccount.issuer;

import com.masonx.common.card.SimulatorCardTokenId;
import com.masonx.virtualaccount.domain.constant.IssuerPartnerType;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

@Service
public class RailSimIssuerCardProviderService implements IssuerCardProviderService {

    private static final String VA_BIN = "999999";

    @Override
    public IssuerPartnerType provider() {
        return IssuerPartnerType.RAIL_SIM;
    }

    @Override
    public CreateIssuerCardResult createCard(CreateIssuerCardCommand command) {
        String testPan = VA_BIN + randomPanSuffix();
        String cardTokenId = SimulatorCardTokenId.fromPan(testPan);
        String maskedPan = VA_BIN + "****" + testPan.substring(testPan.length() - 4);
        return new CreateIssuerCardResult(
                "railsim_" + cardTokenId,
                cardTokenId,
                cardTokenId,
                maskedPan,
                VA_BIN,
                testPan,
                command.expiry(),
                IssuerCardStatus.ACTIVE);
    }

    @Override
    public IssuerCardResult lockCard(String externalCardId, String reason, String idempotencyKey) {
        return new IssuerCardResult(externalCardId, IssuerCardStatus.LOCKED);
    }

    @Override
    public IssuerCardResult unlockCard(String externalCardId, String idempotencyKey) {
        return new IssuerCardResult(externalCardId, IssuerCardStatus.ACTIVE);
    }

    @Override
    public IssuerCardResult terminateCard(String externalCardId, String reason, String idempotencyKey) {
        return new IssuerCardResult(externalCardId, IssuerCardStatus.TERMINATED);
    }

    private static String randomPanSuffix() {
        return String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
    }
}
