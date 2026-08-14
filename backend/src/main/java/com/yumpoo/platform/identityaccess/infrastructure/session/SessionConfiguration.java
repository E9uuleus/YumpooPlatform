package com.yumpoo.platform.identityaccess.infrastructure.session;

import com.yumpoo.platform.identityaccess.application.session.SecureSessionCredentialGenerator;
import com.yumpoo.platform.identityaccess.application.session.SessionCredentialGenerator;
import com.yumpoo.platform.identityaccess.application.session.SessionKeyRing;
import com.yumpoo.platform.identityaccess.application.session.SessionSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionProperties.class)
public class SessionConfiguration {

    @Bean
    SessionSettings sessionSettings(SessionProperties properties) {
        if (!Duration.ofHours(24).equals(properties.getRevokedRetention())) {
            throw new IllegalStateException("yumpoo.session.revoked-retention must be exactly 24h");
        }
        return new SessionSettings(
                properties.getIdleTimeout(),
                properties.getAbsoluteTimeout(),
                properties.getRevokedRetention(),
                properties.getPurgeBatchSize(),
                properties.getPurgeMaxBatches()
        );
    }

    @Bean
    SessionCredentialGenerator sessionCredentialGenerator() {
        return new SecureSessionCredentialGenerator();
    }

    @Bean
    SessionKeyRing sessionKeyRing(SessionProperties properties, Environment environment) {
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        byte[] currentSecret;
        if (properties.getCurrentKey() == null || properties.getCurrentKey().isBlank()) {
            if (production) {
                throw invalid("yumpoo.session.current-key");
            }
            currentSecret = new byte[32];
            new SecureRandom().nextBytes(currentSecret);
        } else {
            currentSecret = decode(properties.getCurrentKey(), "yumpoo.session.current-key");
        }

        SessionKeyRing.Key current = new SessionKeyRing.Key(
                properties.getCurrentKeyVersion(),
                currentSecret,
                null
        );
        boolean anyPrevious = hasText(properties.getPreviousKeyVersion())
                || hasText(properties.getPreviousKey())
                || hasText(properties.getPreviousAcceptUntil());
        if (!anyPrevious) {
            return new SessionKeyRing(current, null);
        }
        if (!hasText(properties.getPreviousKeyVersion())
                || !hasText(properties.getPreviousKey())
                || !hasText(properties.getPreviousAcceptUntil())) {
            throw invalid("yumpoo.session.previous-*");
        }
        Instant acceptUntil;
        try {
            acceptUntil = Instant.parse(properties.getPreviousAcceptUntil());
        } catch (DateTimeParseException exception) {
            throw invalid("yumpoo.session.previous-accept-until");
        }
        SessionKeyRing.Key previous = new SessionKeyRing.Key(
                properties.getPreviousKeyVersion(),
                decode(properties.getPreviousKey(), "yumpoo.session.previous-key"),
                acceptUntil
        );
        return new SessionKeyRing(current, previous);
    }

    private static byte[] decode(String value, String property) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length < 32) {
                throw invalid(property);
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw invalid(property);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static IllegalStateException invalid(String property) {
        return new IllegalStateException("SESSION_SECRET_INVALID:" + property);
    }
}
