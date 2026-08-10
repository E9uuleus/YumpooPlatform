package com.yumpoo.platform.identityaccess.application.oauth;

import java.time.Instant;

public interface OAuthAttemptStore {

    void create(OAuthAttempt attempt);

    /**
     * 仅当 state、nonce 同时匹配、未过期且尚未消费时原子消费；返回前必须提交消费事实。
     */
    boolean consume(
            OAuthAttemptHash stateHash,
            OAuthAttemptHash nonceHash,
            Instant consumedAt
    );
}
