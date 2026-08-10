package com.yumpoo.platform.foundation.api.http;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 生成只包含业务请求身份的稳定 SHA-256 摘要。
 *
 * <p>调用方只能传入 operationId、路径参数、JSON 请求体和附件内容摘要；Cookie、
 * requestId、CSRF 及其他传输期元数据不属于该接口，也不会进入摘要。</p>
 */
@Component
public final class IdempotencyRequestHasher {

    private static final byte OPERATION_ID = 1;
    private static final byte PATH_PARAMETERS = 2;
    private static final byte BODY = 3;
    private static final byte ATTACHMENT_HASHES = 4;

    private static final byte JSON_NULL = 10;
    private static final byte JSON_FALSE = 11;
    private static final byte JSON_TRUE = 12;
    private static final byte JSON_STRING = 13;
    private static final byte JSON_NUMBER = 14;
    private static final byte JSON_ARRAY = 15;
    private static final byte JSON_OBJECT = 16;

    public RequestHash hash(
            String operationId,
            Map<String, String> pathParameters,
            JsonNode body
    ) {
        return hash(operationId, pathParameters, body, Map.of());
    }

    public RequestHash hash(
            String operationId,
            Map<String, String> pathParameters,
            JsonNode body,
            Map<String, String> attachmentHashes
    ) {
        requireOperationId(operationId);
        Objects.requireNonNull(pathParameters, "pathParameters must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(attachmentHashes, "attachmentHashes must not be null");

        MessageDigest digest = sha256();
        digest.update(OPERATION_ID);
        updateString(digest, operationId);
        digest.update(PATH_PARAMETERS);
        updateStringMap(digest, pathParameters, "pathParameters");
        digest.update(BODY);
        updateJson(digest, body);
        digest.update(ATTACHMENT_HASHES);
        updateStringMap(digest, attachmentHashes, "attachmentHashes");
        return new RequestHash(HexFormat.of().formatHex(digest.digest()));
    }

    private static void updateJson(MessageDigest digest, JsonNode node) {
        switch (node.getNodeType()) {
            case NULL -> digest.update(JSON_NULL);
            case BOOLEAN -> digest.update(node.booleanValue() ? JSON_TRUE : JSON_FALSE);
            case STRING -> {
                digest.update(JSON_STRING);
                updateString(digest, node.stringValue());
            }
            case NUMBER -> {
                digest.update(JSON_NUMBER);
                BigDecimal value = node.decimalValueOpt().orElseThrow(() ->
                        new IllegalArgumentException("body must not contain non-finite numbers")
                );
                updateString(digest, normalizeNumber(value));
            }
            case ARRAY -> {
                digest.update(JSON_ARRAY);
                updateInt(digest, node.size());
                for (int index = 0; index < node.size(); index++) {
                    updateJson(digest, node.get(index));
                }
            }
            case OBJECT -> {
                digest.update(JSON_OBJECT);
                List<Map.Entry<String, JsonNode>> properties = node.properties().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .toList();
                updateInt(digest, properties.size());
                for (Map.Entry<String, JsonNode> property : properties) {
                    updateString(digest, property.getKey());
                    updateJson(digest, property.getValue());
                }
            }
            case BINARY, MISSING, POJO -> throw new IllegalArgumentException(
                    "body must contain standard JSON values only"
            );
        }
    }

    private static String normalizeNumber(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.signum() == 0) {
            return "0";
        }
        long exponent = -(long) normalized.scale();
        return normalized.unscaledValue() + "e" + exponent;
    }

    private static void updateStringMap(
            MessageDigest digest,
            Map<String, String> values,
            String parameterName
    ) {
        List<Map.Entry<String, String>> entries = values.entrySet().stream()
                .map(entry -> Map.entry(
                        Objects.requireNonNull(entry.getKey(), parameterName + " keys must not be null"),
                        Objects.requireNonNull(entry.getValue(), parameterName + " values must not be null")
                ))
                .sorted(Map.Entry.comparingByKey())
                .toList();

        updateInt(digest, entries.size());
        for (Map.Entry<String, String> entry : entries) {
            updateString(digest, entry.getKey());
            updateString(digest, entry.getValue());
        }
    }

    private static void updateString(MessageDigest digest, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, encoded.length);
        digest.update(encoded);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void requireOperationId(String operationId) {
        Objects.requireNonNull(operationId, "operationId must not be null");
        if (operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
    }
}
