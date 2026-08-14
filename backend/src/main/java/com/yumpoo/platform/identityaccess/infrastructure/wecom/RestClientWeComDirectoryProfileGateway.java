package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import com.yumpoo.platform.identityaccess.application.directory.DirectoryOptionalField;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncException;
import com.yumpoo.platform.identityaccess.application.directory.WeComDirectoryProfileGateway;
import com.yumpoo.platform.identityaccess.application.directory.WeComRawMemberProfile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 使用独立成员资料 Secret 的只读成员/部门适配器。 */
public final class RestClientWeComDirectoryProfileGateway
        implements WeComDirectoryProfileGateway {

    private static final String API_BASE_URL = "https://qyapi.weixin.qq.com";
    private static final String GET_MEMBER_PATH = "/cgi-bin/user/get";
    private static final String LIST_DEPARTMENTS_PATH = "/cgi-bin/department/list";
    private static final Set<Long> INVALID_ACCESS_TOKEN_ERRORS = Set.of(40014L, 42001L);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final WeComAccessTokenProvider tokenProvider;

    public RestClientWeComDirectoryProfileGateway(
            RestClient.Builder restClientBuilder,
            String corpId,
            String profileSecret,
            Clock clock
    ) {
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder must not be null")
                .baseUrl(API_BASE_URL)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.tokenProvider = new WeComAccessTokenProvider(
                restClient,
                corpId,
                profileSecret,
                Objects.requireNonNull(clock, "clock must not be null")
        );
    }

    @Override
    public Map<Long, String> fetchDepartmentNames() {
        Map<String, Object> response = authorizedGet(LIST_DEPARTMENTS_PATH, null);
        Object value = response.get("department");
        if (!(value instanceof List<?> departments)) {
            throw malformed("DIRECTORY_DEPARTMENT_RESPONSE_MALFORMED");
        }
        Map<Long, String> result = new LinkedHashMap<>();
        for (Object raw : departments) {
            if (!(raw instanceof Map<?, ?> department)) {
                throw malformed("DIRECTORY_DEPARTMENT_RESPONSE_MALFORMED");
            }
            Long id = longValue(department.get("id"));
            String name = stringValue(department.get("name"));
            if (id == null || id <= 0 || name == null || name.isBlank()) {
                throw malformed("DIRECTORY_DEPARTMENT_RESPONSE_MALFORMED");
            }
            String previous = result.putIfAbsent(id, name.trim());
            if (previous != null && !previous.equals(name.trim())) {
                throw malformed("DIRECTORY_DEPARTMENT_RESPONSE_MALFORMED");
            }
        }
        if (result.isEmpty()) {
            throw new DirectorySyncException(
                    "DIRECTORY_DEPARTMENT_SCOPE_EMPTY",
                    "The member profile application could not read any department"
            );
        }
        return Map.copyOf(result);
    }

    @Override
    public WeComRawMemberProfile fetchMemberProfile(String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank() || externalUserId.length() > 256) {
            throw new IllegalArgumentException("externalUserId is invalid");
        }
        Map<String, Object> response = authorizedGet(GET_MEMBER_PATH, externalUserId);
        String returnedId = stringValue(response.get("userid"));
        String displayName = stringValue(response.get("name"));
        if (returnedId == null || displayName == null || displayName.isBlank()) {
            throw new DirectorySyncException(
                    "DIRECTORY_PROFILE_NAME_UNAVAILABLE",
                    "The member profile application could not read a required display name"
            );
        }
        Object departmentValue = response.get("department");
        if (!(departmentValue instanceof List<?> rawDepartmentIds) || rawDepartmentIds.isEmpty()) {
            throw new DirectorySyncException(
                    "DIRECTORY_PROFILE_DEPARTMENT_UNAVAILABLE",
                    "The member profile application could not read a required department"
            );
        }
        LinkedHashSet<Long> departmentIds = new LinkedHashSet<>();
        for (Object rawDepartmentId : rawDepartmentIds) {
            Long departmentId = longValue(rawDepartmentId);
            if (departmentId == null || departmentId <= 0) {
                throw malformed("DIRECTORY_PROFILE_RESPONSE_MALFORMED");
            }
            departmentIds.add(departmentId);
        }
        return new WeComRawMemberProfile(
                returnedId,
                displayName,
                optionalField(response, "email"),
                optionalField(response, "mobile"),
                List.copyOf(departmentIds)
        );
    }

    private Map<String, Object> authorizedGet(String path, String memberId) {
        String token = currentToken();
        Map<String, Object> response = get(path, token, memberId);
        long errorCode = requiredErrorCode(response);
        if (INVALID_ACCESS_TOKEN_ERRORS.contains(errorCode)) {
            tokenProvider.invalidate(token);
            token = currentToken();
            response = get(path, token, memberId);
            errorCode = requiredErrorCode(response);
            if (INVALID_ACCESS_TOKEN_ERRORS.contains(errorCode)) {
                tokenProvider.invalidate(token);
            }
        }
        if (errorCode != 0) {
            throw providerFailure(errorCode);
        }
        return response;
    }

    private String currentToken() {
        try {
            return tokenProvider.currentAccessToken();
        } catch (WeComAccessTokenProvider.AccessTokenException exception) {
            throw new DirectorySyncException(
                    "DIRECTORY_PROFILE_TOKEN_UNAVAILABLE",
                    "The member profile access token was unavailable"
            );
        }
    }

    private Map<String, Object> get(String path, String token, String memberId) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder.path(path).queryParam("access_token", token);
                        if (memberId != null) {
                            builder.queryParam("userid", memberId);
                        }
                        return builder.build();
                    })
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JSON_OBJECT_TYPE);
            if (response == null) {
                throw malformed("DIRECTORY_PROFILE_RESPONSE_MALFORMED");
            }
            return response;
        } catch (DirectorySyncException exception) {
            throw exception;
        } catch (HttpMessageConversionException exception) {
            throw malformed("DIRECTORY_PROFILE_RESPONSE_MALFORMED");
        } catch (RestClientException | IllegalArgumentException exception) {
            // Cause 可能含 token、userid 或响应正文，禁止保留。
            throw new DirectorySyncException(
                    "DIRECTORY_PROFILE_TRANSPORT_FAILED",
                    "The member profile provider request failed"
            );
        }
    }

    private static DirectoryOptionalField optionalField(
            Map<String, Object> response,
            String key
    ) {
        if (!response.containsKey(key) || response.get(key) == null) {
            return DirectoryOptionalField.unavailable();
        }
        Object value = response.get(key);
        if (!(value instanceof String stringValue)) {
            throw malformed("DIRECTORY_PROFILE_RESPONSE_MALFORMED");
        }
        return stringValue.isBlank()
                ? DirectoryOptionalField.clear()
                : DirectoryOptionalField.present(stringValue);
    }

    private static long requiredErrorCode(Map<String, Object> response) {
        Long value = longValue(response.get("errcode"));
        if (value == null) {
            throw malformed("DIRECTORY_PROFILE_RESPONSE_MALFORMED");
        }
        return value;
    }

    private static DirectorySyncException providerFailure(long errorCode) {
        String code = switch ((int) errorCode) {
            case -1 -> "DIRECTORY_PROFILE_SYSTEM_BUSY";
            case 40001 -> "DIRECTORY_PROFILE_INVALID_CREDENTIALS";
            case 40014, 42001 -> "DIRECTORY_PROFILE_TOKEN_REJECTED";
            case 45009 -> "DIRECTORY_PROFILE_RATE_LIMITED";
            case 48002 -> "DIRECTORY_PROFILE_PERMISSION_DENIED";
            case 60020 -> "DIRECTORY_PROFILE_UNTRUSTED_IP";
            default -> "DIRECTORY_PROFILE_PROVIDER_FAILED";
        };
        return new DirectorySyncException(code, "The member profile provider rejected the request");
    }

    private static DirectorySyncException malformed(String code) {
        return new DirectorySyncException(code, "The member profile provider returned malformed data");
    }

    private static Long longValue(Object value) {
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

    private static String stringValue(Object value) {
        return value instanceof String stringValue ? stringValue : null;
    }
}
