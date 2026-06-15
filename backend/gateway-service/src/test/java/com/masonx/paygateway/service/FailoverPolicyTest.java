package com.masonx.paygateway.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FailoverPolicyTest {

    private final FailoverPolicy policy = new FailoverPolicy();

    @Test
    void simulatorDecline_isHardDeclineForOfflinePolicyTests() {
        assertThat(policy.isRetryable("simulator_declined")).isFalse();
    }

    @Test
    void providerTimeout_remainsRetryable() {
        assertThat(policy.isRetryable("provider_timeout")).isTrue();
    }
}
