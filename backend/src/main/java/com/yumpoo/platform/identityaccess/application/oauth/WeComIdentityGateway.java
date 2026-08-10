package com.yumpoo.platform.identityaccess.application.oauth;

import java.net.URI;

public interface WeComIdentityGateway {

    URI buildAuthorizationUri(String state);

    WeComMemberIdentity exchangeCode(String code);
}
