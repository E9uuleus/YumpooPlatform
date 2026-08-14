package com.yumpoo.platform.identityaccess.application.authentication;

import com.yumpoo.platform.identityaccess.application.oauth.WeComMemberIdentity;

import java.net.URI;

/** 正式 Web 登录使用的身份提供者边界；生产企微与 local/test 受控实现共享此端口。 */
public interface WebIdentityProvider {

    String expectedCorpId();

    URI buildAuthorizationUri(String state);

    WeComMemberIdentity exchangeCode(String code);
}
