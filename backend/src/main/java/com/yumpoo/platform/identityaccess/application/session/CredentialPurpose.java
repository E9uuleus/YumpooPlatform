package com.yumpoo.platform.identityaccess.application.session;

public enum CredentialPurpose {
    SESSION("yumpoo:session:v1:"),
    CSRF("yumpoo:csrf:v1:");

    private final String prefix;

    CredentialPurpose(String prefix) {
        this.prefix = prefix;
    }

    String prefix() {
        return prefix;
    }
}
