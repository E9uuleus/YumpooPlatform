package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import java.net.URI;
import java.util.Objects;

record WeComOAuthClientSettings(
        String corpId,
        String agentId,
        String appSecret,
        URI callbackUri
) {

    WeComOAuthClientSettings {
        corpId = requireText(corpId, "corpId");
        agentId = requireText(agentId, "agentId");
        appSecret = requireText(appSecret, "appSecret");
        Objects.requireNonNull(callbackUri, "callbackUri must not be null");
    }

    static WeComOAuthClientSettings from(M012WeComProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        return new WeComOAuthClientSettings(
                properties.getCorpId(),
                properties.getAgentId(),
                properties.getAppSecret(),
                properties.getCallbackUri()
        );
    }

    @Override
    public String toString() {
        return "WeComOAuthClientSettings[corpId=REDACTED, agentId="
                + agentId
                + ", appSecret=REDACTED, callbackUri="
                + callbackUri
                + "]";
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
