package com.yumpoo.platform.identityaccess.application.oauth;

import java.util.Objects;

/** 仅供受信任 API 边界生成脱敏签名收据，禁止直接序列化。 */
public final class VerifiedWeComIdentity {

    private final String corpId;
    private final String memberId;

    public VerifiedWeComIdentity(String corpId, String memberId) {
        this.corpId = Objects.requireNonNull(corpId, "corpId must not be null");
        this.memberId = Objects.requireNonNull(memberId, "memberId must not be null");
    }

    public String corpId() {
        return corpId;
    }

    public String memberId() {
        return memberId;
    }

    @Override
    public String toString() {
        return "VerifiedWeComIdentity[REDACTED]";
    }
}
