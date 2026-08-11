package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.identityaccess.infrastructure.wecom.M015WeComLiveVerificationConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

class M015DesktopAuthGateTest {

    @Test
    void controllerAndConfigurationRequireTheExactProfileAndEnableFlag() {
        assertGate(M015DesktopAuthProbeController.class);
        assertGate(M015WeComLiveVerificationConfiguration.class);
    }

    private static void assertGate(Class<?> type) {
        Profile profile = type.getAnnotation(Profile.class);
        ConditionalOnProperty property = type.getAnnotation(ConditionalOnProperty.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("m0-15-live");
        assertThat(property).isNotNull();
        assertThat(property.prefix()).isEqualTo("yumpoo.m015.wecom");
        assertThat(property.name()).containsExactly("enabled");
        assertThat(property.havingValue()).isEqualTo("true");
        assertThat(property.matchIfMissing()).isFalse();
    }
}
