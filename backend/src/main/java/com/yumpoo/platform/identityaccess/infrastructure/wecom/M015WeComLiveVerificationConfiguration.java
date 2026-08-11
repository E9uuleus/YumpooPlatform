package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthAttemptStore;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthTokenHasher;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthenticationService;
import com.yumpoo.platform.identityaccess.application.desktopauth.M015VerificationReceiptSigner;
import com.yumpoo.platform.identityaccess.application.desktopauth.SecureDesktopAuthTokenGenerator;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptHasher;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptStore;
import com.yumpoo.platform.identityaccess.application.oauth.SecureOAuthAttemptTokenGenerator;
import com.yumpoo.platform.identityaccess.application.oauth.WeComIdentityGateway;
import com.yumpoo.platform.identityaccess.application.oauth.WeComOAuthVerificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@Profile("m0-15-live")
@ConditionalOnProperty(prefix = "yumpoo.m015.wecom", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(M015WeComProperties.class)
public class M015WeComLiveVerificationConfiguration {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    M015VerificationReceiptSigner m015VerificationReceiptSigner(
            @Value("${yumpoo.m015.evidence-hmac-key:}") String evidenceHmacKey,
            M015WeComProperties properties,
            Clock clock
    ) {
        properties.validateForEnabled();
        if (MessageDigest.isEqual(
                evidenceHmacKey.getBytes(StandardCharsets.UTF_8),
                properties.getAppSecret().getBytes(StandardCharsets.UTF_8)
        )) {
            throw new IllegalStateException(
                    "M0-15 evidence HMAC key must be independent from the WeCom app secret"
            );
        }
        return new M015VerificationReceiptSigner(evidenceHmacKey, clock);
    }

    @Bean
    DesktopAuthenticationService m015DesktopAuthenticationService(
            OAuthAttemptStore oauthAttemptStore,
            DesktopAuthAttemptStore desktopAuthAttemptStore,
            M015WeComProperties properties,
            M015VerificationReceiptSigner receiptSigner,
            Clock clock
    ) {
        properties.validateForEnabled();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        WeComIdentityGateway identityGateway = new RestClientWeComIdentityGateway(
                RestClient.builder().requestFactory(requestFactory),
                properties.clientSettings(),
                clock
        );
        WeComOAuthVerificationService oauthService = new WeComOAuthVerificationService(
                oauthAttemptStore,
                identityGateway,
                new SecureOAuthAttemptTokenGenerator(),
                new OAuthAttemptHasher(),
                clock,
                properties.getCorpId(),
                properties.getAllowedMemberIds()
        );
        return new DesktopAuthenticationService(
                oauthService,
                desktopAuthAttemptStore,
                new SecureDesktopAuthTokenGenerator(),
                new DesktopAuthTokenHasher(),
                receiptSigner,
                clock
        );
    }
}
