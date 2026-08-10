package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import com.yumpoo.platform.identityaccess.application.oauth.M012VerificationReceiptSigner;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptHasher;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptStore;
import com.yumpoo.platform.identityaccess.application.oauth.SecureOAuthAttemptTokenGenerator;
import com.yumpoo.platform.identityaccess.application.oauth.WeComIdentityGateway;
import com.yumpoo.platform.identityaccess.application.oauth.WeComOAuthVerificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Profile("m0-12-live")
@ConditionalOnProperty(prefix = "yumpoo.m012.wecom", name = "enabled", havingValue = "true")
public class M012WeComLiveVerificationConfiguration {

    @Bean
    WeComOAuthVerificationService weComOAuthVerificationService(
            OAuthAttemptStore attemptStore,
            WeComIdentityGateway identityGateway,
            M012WeComProperties properties,
            Clock clock
    ) {
        properties.validateForEnabled();
        return new WeComOAuthVerificationService(
                attemptStore,
                identityGateway,
                new SecureOAuthAttemptTokenGenerator(),
                new OAuthAttemptHasher(),
                clock,
                properties.getCorpId(),
                properties.getAllowedMemberIds()
        );
    }

    @Bean
    M012VerificationReceiptSigner m012VerificationReceiptSigner(
            @Value("${yumpoo.m012.evidence-hmac-key:}") String evidenceHmacKey,
            M012WeComProperties properties,
            Clock clock
    ) {
        properties.validateForEnabled();
        if (MessageDigest.isEqual(
                evidenceHmacKey.getBytes(StandardCharsets.UTF_8),
                properties.getAppSecret().getBytes(StandardCharsets.UTF_8)
        )) {
            throw new IllegalStateException(
                    "M0-12 evidence HMAC key must be independent from the WeCom app secret"
            );
        }
        return new M012VerificationReceiptSigner(evidenceHmacKey, clock);
    }
}
