package com.yumpoo.platform.identityaccess.application.oauth;

@FunctionalInterface
public interface OAuthAttemptTokenGenerator {

    OAuthAttemptToken generate();
}
