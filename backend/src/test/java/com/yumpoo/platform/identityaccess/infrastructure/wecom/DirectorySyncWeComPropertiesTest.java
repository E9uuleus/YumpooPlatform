package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirectorySyncWeComPropertiesTest {

    @Test
    void exposesFrozenDefaultsAndRequiresTwoDifferentCredentialsWhenEnabled() {
        DirectorySyncWeComProperties properties = enabledProperties();

        properties.validateForEnabled();

        assertThat(properties.getPageSize()).isEqualTo(1000);
        assertThat(properties.getLeaseDuration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(20));

        properties.setProfileSecret("directory-secret");
        assertThatThrownBy(properties::validateForEnabled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("M1-04 WeCom directory configuration is invalid");
    }

    @Test
    void rejectsTimeoutsThatCanOutliveTheLease() {
        DirectorySyncWeComProperties properties = enabledProperties();
        properties.setReadTimeout(Duration.ofMinutes(5));

        assertThatThrownBy(properties::validateForEnabled)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsEmptyExternalConfigurationWhileFeatureIsDisabled() {
        DirectorySyncWeComProperties properties = new DirectorySyncWeComProperties();

        properties.validateForEnabled();

        assertThat(properties.isEnabled()).isFalse();
    }

    private static DirectorySyncWeComProperties enabledProperties() {
        DirectorySyncWeComProperties properties = new DirectorySyncWeComProperties();
        properties.setEnabled(true);
        properties.setCorpId("ww-test");
        properties.setDirectorySecret("directory-secret");
        properties.setProfileSecret("profile-secret");
        return properties;
    }
}
