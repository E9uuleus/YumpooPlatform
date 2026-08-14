package com.yumpoo.platform.identityaccess.api;

final class WebAuthenticationPaths {

    static final String AUTHORIZE = "/api/v1/auth/wecom/authorize";
    static final String CALLBACK = "/api/v1/auth/wecom/callback";
    static final String LOGOUT = "/api/v1/auth/logout";
    static final String ME = "/api/v1/auth/me";

    private WebAuthenticationPaths() {
    }
}
