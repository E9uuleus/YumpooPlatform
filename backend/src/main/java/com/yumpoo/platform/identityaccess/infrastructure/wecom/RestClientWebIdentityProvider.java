package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import com.yumpoo.platform.identityaccess.application.authentication.WebIdentityProvider;
import com.yumpoo.platform.identityaccess.application.oauth.WeComMemberIdentity;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;

final class RestClientWebIdentityProvider implements WebIdentityProvider {

    private final String corpId;
    private final WebOAuthProperties properties;
    private final RestClientWeComIdentityGateway delegate;

    RestClientWebIdentityProvider(
            RestClient.Builder builder,
            WebOAuthProperties properties,
            Clock clock
    ) {
        this.corpId = properties.getCorpId();
        this.properties = properties;
        this.delegate = new RestClientWeComIdentityGateway(
                builder,
                new WeComOAuthClientSettings(
                        properties.getCorpId(),
                        properties.getAgentId(),
                        properties.getAppSecret(),
                        properties.getCallbackUri()
                ),
                clock
        );
    }

    @Override
    public String expectedCorpId() {
        return corpId;
    }

    @Override
    public URI buildAuthorizationUri(String state) {
        return delegate.buildQrAuthorizationUri(state, properties.getCallbackUri());
    }

    @Override
    public URI buildElectronAuthorizationUri(String state) {
        return delegate.buildQrAuthorizationUri(state, properties.getElectronCallbackUri());
    }

    @Override
    public WeComMemberIdentity exchangeCode(String code) {
        return delegate.exchangeCode(code);
    }
}
