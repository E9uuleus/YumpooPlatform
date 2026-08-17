package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import com.yumpoo.platform.identityaccess.application.authentication.WebIdentityProvider;
import com.yumpoo.platform.identityaccess.application.oauth.WeComDependencyUnavailableException;
import com.yumpoo.platform.identityaccess.application.oauth.WeComMemberIdentity;

import java.net.URI;

final class DisabledWebIdentityProvider implements WebIdentityProvider {

    @Override
    public String expectedCorpId() {
        return "disabled";
    }

    @Override
    public URI buildAuthorizationUri(String state) {
        throw new WeComDependencyUnavailableException();
    }

    @Override
    public URI buildElectronAuthorizationUri(String state) {
        throw new WeComDependencyUnavailableException();
    }

    @Override
    public WeComMemberIdentity exchangeCode(String code) {
        throw new WeComDependencyUnavailableException();
    }
}
