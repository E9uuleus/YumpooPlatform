package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class PropertiesWeComConfigurationStatusProviderTest {

    @Test
    void exposesOnlyConfigurationStateAndMaskedCorpIdentifier() {
        WebOAuthProperties oauth = new WebOAuthProperties();
        oauth.setEnabled(true);
        oauth.setCorpId("ww-corp-12345678");
        oauth.setAgentId("100001");
        oauth.setAppSecret("oauth-super-secret");
        oauth.setCallbackUri(URI.create("https://example.test/api/v1/auth/wecom/callback"));

        DirectorySyncWeComProperties directory = new DirectorySyncWeComProperties();
        directory.setEnabled(true);
        directory.setCorpId("ww-corp-12345678");
        directory.setDirectorySecret("directory-super-secret");
        directory.setProfileSecret("profile-super-secret");

        var status = new PropertiesWeComConfigurationStatusProvider(oauth, directory).current();

        assertThat(status.oauth().configured()).isTrue();
        assertThat(status.directory().configured()).isTrue();
        assertThat(status.corpIdConsistent()).isTrue();
        assertThat(status.oauth().corpIdMasked()).isEqualTo("****5678");
        assertThat(status.directory().corpIdMasked()).isEqualTo("****5678");
        assertThat(status.toString())
                .doesNotContain("oauth-super-secret", "directory-super-secret",
                        "profile-super-secret", "ww-corp-12345678");
    }
}
