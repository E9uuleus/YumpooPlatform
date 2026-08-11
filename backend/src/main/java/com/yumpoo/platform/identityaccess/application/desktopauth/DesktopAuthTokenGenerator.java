package com.yumpoo.platform.identityaccess.application.desktopauth;

@FunctionalInterface
public interface DesktopAuthTokenGenerator {

    DesktopAuthToken generate();
}
