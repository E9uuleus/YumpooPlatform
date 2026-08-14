package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import com.yumpoo.platform.identityaccess.application.administration.WeComConfigurationStatus;
import com.yumpoo.platform.identityaccess.application.administration.WeComConfigurationStatusProvider;
import org.springframework.stereotype.Component;

@Component
public final class PropertiesWeComConfigurationStatusProvider
        implements WeComConfigurationStatusProvider {

    private final WebOAuthProperties oauth;
    private final DirectorySyncWeComProperties directory;

    public PropertiesWeComConfigurationStatusProvider(
            WebOAuthProperties oauth,
            DirectorySyncWeComProperties directory
    ) {
        this.oauth = oauth;
        this.directory = directory;
    }

    @Override
    public WeComConfigurationStatus current() {
        boolean oauthCorpConfigured = present(oauth.getCorpId());
        boolean directoryCorpConfigured = present(directory.getCorpId());
        boolean agentConfigured = present(oauth.getAgentId());
        boolean appSecretConfigured = present(oauth.getAppSecret());
        boolean callbackConfigured = oauth.getCallbackUri() != null;
        boolean directorySecretConfigured = present(directory.getDirectorySecret());
        boolean profileSecretConfigured = present(directory.getProfileSecret());

        return new WeComConfigurationStatus(
                new WeComConfigurationStatus.OAuthStatus(
                        oauth.isEnabled(),
                        oauthCorpConfigured && agentConfigured
                                && appSecretConfigured && callbackConfigured,
                        mask(oauth.getCorpId()),
                        agentConfigured,
                        appSecretConfigured,
                        callbackConfigured
                ),
                new WeComConfigurationStatus.DirectoryStatus(
                        directory.isEnabled(),
                        directoryCorpConfigured && directorySecretConfigured
                                && profileSecretConfigured,
                        mask(directory.getCorpId()),
                        directorySecretConfigured,
                        profileSecretConfigured
                ),
                !oauthCorpConfigured || !directoryCorpConfigured
                        || oauth.getCorpId().equals(directory.getCorpId())
        );
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String mask(String value) {
        if (!present(value)) {
            return null;
        }
        int visible = Math.min(4, value.length());
        return "****" + value.substring(value.length() - visible);
    }
}
