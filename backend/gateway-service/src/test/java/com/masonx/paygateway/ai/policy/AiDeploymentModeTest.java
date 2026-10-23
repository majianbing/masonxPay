package com.masonx.paygateway.ai.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiDeploymentModeTest {

    @Test
    void disabled_allowsNoModelCalls() {
        assertThat(AiDeploymentMode.DISABLED.allowsModelCalls()).isFalse();
        assertThat(AiDeploymentMode.DISABLED.allowsExternalProvider()).isFalse();
    }

    @Test
    void internalOnly_allowsModelCallsButNoExternalProvider() {
        assertThat(AiDeploymentMode.INTERNAL_ONLY.allowsModelCalls()).isTrue();
        assertThat(AiDeploymentMode.INTERNAL_ONLY.allowsExternalProvider()).isFalse();
    }

    @Test
    void externalModes_allowExternalProvider() {
        assertThat(AiDeploymentMode.EXTERNAL_REDACTED.allowsExternalProvider()).isTrue();
        assertThat(AiDeploymentMode.EXTERNAL_RESTRICTED.allowsExternalProvider()).isTrue();
    }

    @Test
    void fromConfigValue_blankOrNull_defaultsToDisabled() {
        assertThat(AiDeploymentMode.fromConfigValue(null)).isEqualTo(AiDeploymentMode.DISABLED);
        assertThat(AiDeploymentMode.fromConfigValue("  ")).isEqualTo(AiDeploymentMode.DISABLED);
    }

    @ParameterizedTest
    @EnumSource(AiDeploymentMode.class)
    void fromConfigValue_roundTripsKebabCase(AiDeploymentMode mode) {
        assertThat(mode.configValue()).doesNotContain("_").isLowerCase();
        assertThat(AiDeploymentMode.fromConfigValue(mode.configValue())).isEqualTo(mode);
        assertThat(AiDeploymentMode.fromConfigValue(mode.configValue().toUpperCase())).isEqualTo(mode);
    }

    @Test
    void fromConfigValue_unknownValue_fails() {
        assertThatThrownBy(() -> AiDeploymentMode.fromConfigValue("autonomous"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
