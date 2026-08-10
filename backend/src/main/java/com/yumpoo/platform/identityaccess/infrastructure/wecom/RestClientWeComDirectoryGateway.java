package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import com.yumpoo.platform.identityaccess.application.directory.DirectorySnapshotFailure;
import com.yumpoo.platform.identityaccess.application.directory.WeComDirectoryGateway;
import com.yumpoo.platform.identityaccess.application.directory.WeComDirectoryGatewayException;
import com.yumpoo.platform.identityaccess.application.directory.WeComDirectoryPage;
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

/** 基于通讯录同步 Secret 的企业微信成员 ID 分页适配器。 */
public final class RestClientWeComDirectoryGateway implements WeComDirectoryGateway {

    private static final String API_BASE_URL = "https://qyapi.weixin.qq.com";
    private static final String LIST_MEMBER_IDS_PATH = "/cgi-bin/user/list_id";
    private static final Set<Long> INVALID_ACCESS_TOKEN_ERRORS = Set.of(40014L, 42001L);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final WeComAccessTokenProvider accessTokenProvider;

    public RestClientWeComDirectoryGateway(
            RestClient.Builder restClientBuilder,
            String corpId,
            String directorySecret,
            Clock clock
    ) {
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder must not be null")
                .baseUrl(API_BASE_URL)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.accessTokenProvider = new WeComAccessTokenProvider(
                restClient,
                corpId,
                directorySecret,
                Objects.requireNonNull(clock, "clock must not be null")
        );
    }

    @Override
    public WeComDirectoryPage fetchPage(String cursor, int limit) {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("limit must be between 1 and 10000");
        }
        if (cursor != null && cursor.length() > 4096) {
            throw new IllegalArgumentException("cursor is too long");
        }

        String accessToken = currentAccessToken();
        Map<String, Object> response = fetchPage(accessToken, cursor, limit);
        long errorCode = requiredErrorCode(response);
        if (INVALID_ACCESS_TOKEN_ERRORS.contains(errorCode)) {
            accessTokenProvider.invalidate(accessToken);
            accessToken = currentAccessToken();
            response = fetchPage(accessToken, cursor, limit);
            errorCode = requiredErrorCode(response);
            if (INVALID_ACCESS_TOKEN_ERRORS.contains(errorCode)) {
                accessTokenProvider.invalidate(accessToken);
                throw failure(DirectorySnapshotFailure.ACCESS_TOKEN_REJECTED);
            }
        }

        if (errorCode != 0) {
            throw failure(classify(errorCode));
        }
        return parsePage(response);
    }

    private String currentAccessToken() {
        try {
            return accessTokenProvider.currentAccessToken();
        } catch (WeComAccessTokenProvider.AccessTokenException exception) {
            throw failure(mapTokenFailure(exception.failure()));
        }
    }

    private Map<String, Object> fetchPage(String accessToken, String cursor, int limit) {
        Map<String, Object> request = new LinkedHashMap<>();
        if (cursor != null && !cursor.isBlank()) {
            request.put("cursor", cursor);
        }
        request.put("limit", limit);

        try {
            Map<String, Object> response = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path(LIST_MEMBER_IDS_PATH)
                            .queryParam("access_token", accessToken)
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JSON_OBJECT_TYPE);
            if (response == null) {
                throw failure(DirectorySnapshotFailure.MALFORMED_RESPONSE);
            }
            return response;
        } catch (WeComDirectoryGatewayException exception) {
            throw exception;
        } catch (HttpMessageConversionException exception) {
            throw failure(DirectorySnapshotFailure.MALFORMED_RESPONSE);
        } catch (RestClientException exception) {
            // RestClient 异常可能含 token、cursor 或响应正文，禁止保留 cause。
            DirectorySnapshotFailure classification = isResponseDecodingFailure(exception)
                    ? DirectorySnapshotFailure.MALFORMED_RESPONSE
                    : DirectorySnapshotFailure.TRANSPORT_ERROR;
            throw failure(classification);
        } catch (IllegalArgumentException exception) {
            throw failure(DirectorySnapshotFailure.TRANSPORT_ERROR);
        }
    }

    private static boolean isResponseDecodingFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < 16 && current != null; depth++) {
            if (current instanceof HttpMessageConversionException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    private static WeComDirectoryPage parsePage(Map<String, Object> response) {
        Object membersValue = response.get("dept_user");
        Object cursorValue = response.get("next_cursor");
        if (!(membersValue instanceof List<?> rawMembers)) {
            throw failure(DirectorySnapshotFailure.MALFORMED_MEMBER_LIST);
        }

        LinkedHashSet<String> memberIds = new LinkedHashSet<>();
        for (Object rawMember : rawMembers) {
            if (!(rawMember instanceof Map<?, ?> member)) {
                throw failure(DirectorySnapshotFailure.MALFORMED_MEMBER);
            }
            Object memberIdValue = member.get("userid");
            if (!(memberIdValue instanceof String memberId)
                    || memberId.isBlank()
                    || memberId.length() > 256) {
                throw failure(DirectorySnapshotFailure.MALFORMED_MEMBER);
            }
            memberIds.add(memberId);
        }

        List<String> parsedMemberIds = new ArrayList<>(memberIds);
        if (!response.containsKey("next_cursor") || cursorValue == null) {
            return WeComDirectoryPage.omitted(parsedMemberIds);
        }
        if (!(cursorValue instanceof String nextCursor)) {
            throw failure(DirectorySnapshotFailure.INVALID_CURSOR_TYPE);
        }

        try {
            return nextCursor.isEmpty()
                    ? WeComDirectoryPage.explicitEnd(parsedMemberIds)
                    : WeComDirectoryPage.next(parsedMemberIds, nextCursor);
        } catch (IllegalArgumentException exception) {
            throw failure(DirectorySnapshotFailure.INVALID_CURSOR_VALUE);
        }
    }

    private static long requiredErrorCode(Map<String, Object> response) {
        Object value = response.get("errcode");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Long.parseLong(stringValue);
            } catch (NumberFormatException ignored) {
                throw failure(DirectorySnapshotFailure.MALFORMED_RESPONSE);
            }
        }
        throw failure(DirectorySnapshotFailure.MALFORMED_RESPONSE);
    }

    private static DirectorySnapshotFailure classify(long errorCode) {
        return switch ((int) errorCode) {
            case -1 -> DirectorySnapshotFailure.SYSTEM_BUSY;
            case 40001 -> DirectorySnapshotFailure.INVALID_CREDENTIALS;
            case 45009 -> DirectorySnapshotFailure.RATE_LIMITED;
            case 48002 -> DirectorySnapshotFailure.PERMISSION_DENIED;
            case 60020 -> DirectorySnapshotFailure.UNTRUSTED_IP;
            default -> DirectorySnapshotFailure.PROVIDER_ERROR;
        };
    }

    private static DirectorySnapshotFailure mapTokenFailure(
            WeComAccessTokenProvider.Failure failure
    ) {
        return switch (failure) {
            case SYSTEM_BUSY -> DirectorySnapshotFailure.SYSTEM_BUSY;
            case INVALID_CREDENTIALS -> DirectorySnapshotFailure.INVALID_CREDENTIALS;
            case ACCESS_TOKEN_REJECTED -> DirectorySnapshotFailure.ACCESS_TOKEN_REJECTED;
            case RATE_LIMITED -> DirectorySnapshotFailure.RATE_LIMITED;
            case PERMISSION_DENIED -> DirectorySnapshotFailure.PERMISSION_DENIED;
            case UNTRUSTED_IP -> DirectorySnapshotFailure.UNTRUSTED_IP;
            case PROVIDER_ERROR -> DirectorySnapshotFailure.PROVIDER_ERROR;
            case TRANSPORT_ERROR -> DirectorySnapshotFailure.TRANSPORT_ERROR;
            case MALFORMED_RESPONSE -> DirectorySnapshotFailure.MALFORMED_RESPONSE;
        };
    }

    private static WeComDirectoryGatewayException failure(DirectorySnapshotFailure failure) {
        return new WeComDirectoryGatewayException(failure);
    }
}
