package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import com.yumpoo.platform.identityaccess.application.oauth.WeComAuthenticationFailedException;
import com.yumpoo.platform.identityaccess.application.oauth.WeComDependencyUnavailableException;
import com.yumpoo.platform.identityaccess.application.oauth.WeComIdentityGateway;
import com.yumpoo.platform.identityaccess.application.oauth.WeComMemberIdentity;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

final class RestClientWeComIdentityGateway implements WeComIdentityGateway {

    private static final String API_BASE_URL = "https://qyapi.weixin.qq.com";
    private static final String AUTHORIZE_ENDPOINT = "https://open.weixin.qq.com/connect/oauth2/authorize";
    private static final String GET_USER_INFO_PATH = "/cgi-bin/auth/getuserinfo";
    private static final Set<Long> INVALID_AUTHORIZATION_CODE_ERRORS = Set.of(
            40029L,
            40163L,
            42003L,
            42022L
    );
    private static final Set<Long> INVALID_ACCESS_TOKEN_ERRORS = Set.of(40014L, 42001L);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final M012WeComProperties properties;
    private final WeComAccessTokenProvider accessTokenProvider;

    RestClientWeComIdentityGateway(
            RestClient.Builder restClientBuilder,
            M012WeComProperties properties,
            Clock clock
    ) {
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder must not be null")
                .baseUrl(API_BASE_URL)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.accessTokenProvider = new WeComAccessTokenProvider(
                restClient,
                properties.getCorpId(),
                properties.getAppSecret(),
                Objects.requireNonNull(clock, "clock must not be null")
        );
    }

    @Override
    public URI buildAuthorizationUri(String state) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state must not be blank");
        }
        return UriComponentsBuilder.fromUriString(AUTHORIZE_ENDPOINT)
                .queryParam("appid", "{corpId}")
                .queryParam("redirect_uri", "{redirectUri}")
                .queryParam("response_type", "code")
                .queryParam("scope", "snsapi_base")
                .queryParam("state", "{state}")
                .queryParam("agentid", "{agentId}")
                .fragment("wechat_redirect")
                .encode()
                .buildAndExpand(Map.of(
                        "corpId", properties.getCorpId(),
                        "redirectUri", properties.getCallbackUri().toASCIIString(),
                        "state", state,
                        "agentId", properties.getAgentId()
                ))
                .toUri();
    }

    @Override
    public WeComMemberIdentity exchangeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new WeComAuthenticationFailedException();
        }

        String accessToken = currentAccessToken();
        Map<String, Object> response = fetchMemberIdentity(accessToken, code);
        long errorCode = errorCode(response);
        if (INVALID_ACCESS_TOKEN_ERRORS.contains(errorCode)) {
            invalidateCachedAccessToken(accessToken);
            accessToken = currentAccessToken();
            response = fetchMemberIdentity(accessToken, code);
            errorCode = errorCode(response);
            if (INVALID_ACCESS_TOKEN_ERRORS.contains(errorCode)) {
                invalidateCachedAccessToken(accessToken);
                throw new WeComDependencyUnavailableException();
            }
        }

        if (INVALID_AUTHORIZATION_CODE_ERRORS.contains(errorCode)) {
            throw new WeComAuthenticationFailedException();
        }
        if (errorCode != 0) {
            throw new WeComDependencyUnavailableException();
        }

        String memberId = optionalString(response, "userid", "UserId");
        if (memberId != null) {
            return new WeComMemberIdentity(properties.getCorpId(), memberId);
        }
        if (optionalString(response, "openid", "OpenId") != null) {
            throw new WeComAuthenticationFailedException();
        }
        throw new WeComDependencyUnavailableException();
    }

    private Map<String, Object> fetchMemberIdentity(String accessToken, String code) {
        return getJson(uriBuilder -> uriBuilder
                .path(GET_USER_INFO_PATH)
                .queryParam("access_token", accessToken)
                .queryParam("code", code)
                .build());
    }

    private String currentAccessToken() {
        try {
            return accessTokenProvider.currentAccessToken();
        } catch (WeComAccessTokenProvider.AccessTokenException exception) {
            throw new WeComDependencyUnavailableException();
        }
    }

    private void invalidateCachedAccessToken(String rejectedValue) {
        accessTokenProvider.invalidate(rejectedValue);
    }

    private Map<String, Object> getJson(Function<org.springframework.web.util.UriBuilder, URI> uriFunction) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri(uriFunction)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JSON_OBJECT_TYPE);
            if (response == null) {
                throw new WeComDependencyUnavailableException();
            }
            return response;
        } catch (WeComDependencyUnavailableException exception) {
            throw exception;
        } catch (RestClientException | IllegalArgumentException exception) {
            // RestClient 异常可能含带 Secret/token/code 的请求 URI，禁止作为 cause 向上泄露。
            throw new WeComDependencyUnavailableException();
        }
    }

    private static long errorCode(Map<String, Object> response) {
        Long value = optionalLong(response, "errcode");
        if (value == null) {
            throw new WeComDependencyUnavailableException();
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

    private static String optionalString(Map<String, Object> response, String... keys) {
        for (String key : keys) {
            Object value = response.get(key);
            if (value instanceof String stringValue && !stringValue.isBlank()) {
                return stringValue;
            }
        }
        return null;
    }
}
