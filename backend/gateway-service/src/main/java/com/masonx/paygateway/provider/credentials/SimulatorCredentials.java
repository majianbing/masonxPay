package com.masonx.paygateway.provider.credentials;

/**
 * Non-production fake PSP credentials used by the H7 benchmark simulator.
 * There are no secrets: the account exists so routing and provider dispatch use
 * the same connector-account path as real PSPs.
 *
 * @param successRate probability from 0.0 to 1.0 that a simulator operation succeeds
 */
public record SimulatorCredentials(boolean sandbox, double successRate) implements ProviderCredentials {

    public SimulatorCredentials {
        if (Double.isNaN(successRate)) {
            successRate = 1.0;
        }
        successRate = Math.max(0.0, Math.min(1.0, successRate));
    }

    @Override
    public String clientKey() {
        return "mason-simulator";
    }
}
