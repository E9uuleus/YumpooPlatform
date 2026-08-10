package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** 每个企微 Secret 独占实例和缓存，避免应用 Secret 与通讯录 Secret 混用 token。 */
final class WeComAccessTokenProvider {

    private static final String GET_TOKEN_PATH = "/cgi-bin/gettoken";
    private static final Duration TOKEN_REFRESH_SKEW = Duration.ofSeconds(60);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final String corpId;
    private final String secret;
    private final Clock clock;
    private final Object tokenMonitor = new Object();
    private volatile CachedAccessToken cachedAccessToken;

    WeComAccessTokenProvider(RestClient restClient, String corpId, String secret, Clock clock) {
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
        this.corpId = requireValue(corpId, "corpId");
        this.secret = requireValue(secret, "secret");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    String currentAccessToken() {
        Instant now = clock.instant();
        CachedAccessToken current = cachedAccessToken;
        if (isFresh(current, now)) {
            return current.value();
        }

        synchronized (tokenMonitor) {
            now = clock.instant();
            current = cachedAccessToken;
            if (isFresh(current, now)) {
                return current.value();
            }
            CachedAccessToken fetched = fetchAccessToken(now);
            cachedAccessToken = fetched;
            return fetched.value();
        }
    }

    void invalidate(String rejectedValue) {
        if (rejectedValue == null) {
            return;
        }
        synchronized (tokenMonitor) {
            CachedAccessToken current = cachedAccessToken;
            if (current != null && current.value().equals(rejectedValue)) {
                cachedAccessToken = null;
            }
        }
    }

    private CachedAccessToken fetchAccessToken(Instant fetchedAt) {
        Map<String, Object> response = getJson();
        long errorCode = requiredLong(response, "errcode");
        if (errorCode != 0) {
            throw new AccessTokenException(classify(errorCode));
        }

        String value = optionalString(response, "access_token");
        Long expiresInSeconds = optionalLong(response, "expires_in");
        if (value == null || expiresInSeconds == null || expiresInSeconds <= 0) {
            throw new AccessTokenException(Failure.MALFORMED_RESPONSE);
        }

        try {
            Instant refreshAt = fetchedAt.plusSeconds(expiresInSeconds).minus(TOKEN_REFRESH_SKEW);
            return new CachedAccessToken(value, refreshAt);
        } catch (ArithmeticException | DateTimeException exception) {
            throw new AccessTokenException(Failure.MALFORMED_RESPONSE);
        }
    }

    private Map<String, Object> getJson() {
        try {
            Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(GET_TOKEN_PATH)
                            .queryParam("corpid", corpId)
                            .queryParam("corpsecret", secret)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JSON_OBJECT_TYPE);
            if (response == null) {
                throw new AccessTokenException(Failure.MALFORMED_RESPONSE);
            }
            return response;
        } catch (AccessTokenException exception) {
            throw exception;
        } catch (RestClientException | IllegalArgumentException exception) {
            // RestClient 异常可能含带 Secret 的请求 URI，禁止保留 cause。
            throw new AccessTokenException(Failure.TRANSPORT_ERROR);
        }
    }

    private static boolean isFresh(CachedAccessToken token, Instant now) {
        return token != null && now.isBefore(token.refreshAt());
    }

    private static Failure classify(long errorCode) {
        return switch ((int) errorCode) {
            case -1 -> Failure.SYSTEM_BUSY;
            case 40001 -> Failure.INVALID_CREDENTIALS;
            case 40014, 42001 -> Failure.ACCESS_TOKEN_REJECTED;
            case 45009 -> Failure.RATE_LIMITED;
            case 48002 -> Failure.PERMISSION_DENIED;
            case 60020 -> Failure.UNTRUSTED_IP;
            default -> Failure.PROVIDER_ERROR;
        };
    }

    private static long requiredLong(Map<String, Object> response, String key) {
        Long value = optionalLong(response, key);
        if (value == null) {
            throw new AccessTokenException(Failure.MALFORMED_RESPONSE);
        }
        return value;
    }

    private static Long optionalLong(Map<String, Object> response, String key) {
        Object value = response.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Long.parseLong(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String optionalString(Map<String, Object> response, String key) {
        Object value = response.get(key);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        return null;
    }

    private static String requireValue(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    enum Failure {
        SYSTEM_BUSY,
        INVALID_CREDENTIALS,
        ACCESS_TOKEN_REJECTED,
        RATE_LIMITED,
        PERMISSION_DENIED,
        UNTRUSTED_IP,
        PROVIDER_ERROR,
        TRANSPORT_ERROR,
        MALFORMED_RESPONSE
    }

    static final class AccessTokenException extends RuntimeException {

        private static final String SAFE_MESSAGE = "WeCom access token is unavailable";

        private final Failure failure;

        private AccessTokenException(Failure failure) {
            super(SAFE_MESSAGE);
            this.failure = failure;
        }

        Failure failure() {
            return failure;
        }
    }

    private record CachedAccessToken(String value, Instant refreshAt) {

        @Override
        public String toString() {
            return "CachedAccessToken[REDACTED]";
        }
    }
}
