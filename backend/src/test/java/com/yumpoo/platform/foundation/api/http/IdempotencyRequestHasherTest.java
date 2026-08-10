package com.yumpoo.platform.foundation.api.http;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyRequestHasherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IdempotencyRequestHasher hasher = new IdempotencyRequestHasher();

    @Test
    void recursivelySortsObjectKeysAndNormalizesNumbersAndNull() throws Exception {
        JsonNode first = objectMapper.readTree("""
                {
                  "nested": {"nullValue": null, "number": 1000.00},
                  "amount": 1.2300,
                  "zero": -0.0
                }
                """);
        JsonNode reordered = objectMapper.readTree("""
                {
                  "zero": 0,
                  "amount": 1.23,
                  "nested": {"number": 1e3, "nullValue": null}
                }
                """);

        assertThat(hash(first)).isEqualTo(hash(reordered));
    }

    @Test
    void preservesArrayOrderAndJsonValueTypes() throws Exception {
        RequestHash ordered = hash(objectMapper.readTree("[1, \"1\", true, null]"));
        RequestHash reordered = hash(objectMapper.readTree("[\"1\", 1, true, null]"));
        RequestHash changedType = hash(objectMapper.readTree("[1, 1, true, null]"));

        assertThat(List.of(ordered, reordered, changedType)).doesNotHaveDuplicates();
    }

    @Test
    void sortsPathParametersAndAttachmentHashesIndependentlyOfMapIterationOrder() throws Exception {
        Map<String, String> firstPath = linkedMap("workItemId", "w-1", "projectId", "p-1");
        Map<String, String> secondPath = linkedMap("projectId", "p-1", "workItemId", "w-1");
        Map<String, String> firstAttachments = linkedMap("second", "bbb", "first", "aaa");
        Map<String, String> secondAttachments = linkedMap("first", "aaa", "second", "bbb");
        JsonNode body = objectMapper.readTree("{\"title\":\"same\"}");

        assertThat(hasher.hash("updateWorkItem", firstPath, body, firstAttachments))
                .isEqualTo(hasher.hash("updateWorkItem", secondPath, body, secondAttachments));
    }

    @Test
    void includesOperationPathBodyAndAttachmentContentInTheDigest() throws Exception {
        JsonNode body = objectMapper.readTree("{\"title\":\"first\"}");
        RequestHash baseline = hasher.hash(
                "updateWorkItem",
                Map.of("workItemId", "w-1"),
                body,
                Map.of("content", "aaa")
        );

        assertThat(List.of(
                baseline,
                hasher.hash("replaceWorkItem", Map.of("workItemId", "w-1"), body, Map.of("content", "aaa")),
                hasher.hash("updateWorkItem", Map.of("workItemId", "w-2"), body, Map.of("content", "aaa")),
                hasher.hash(
                        "updateWorkItem",
                        Map.of("workItemId", "w-1"),
                        objectMapper.readTree("{\"title\":\"second\"}"),
                        Map.of("content", "aaa")
                ),
                hasher.hash("updateWorkItem", Map.of("workItemId", "w-1"), body, Map.of("content", "bbb"))
        )).doesNotHaveDuplicates();
    }

    @Test
    void overloadWithoutAttachmentsUsesAnEmptyAttachmentMap() throws Exception {
        JsonNode body = objectMapper.readTree("null");

        assertThat(hasher.hash("createThing", Map.of(), body))
                .isEqualTo(hasher.hash("createThing", Map.of(), body, Map.of()));
    }

    @Test
    void rejectsJacksonSpecificNonJsonNodes() {
        assertThatThrownBy(() -> hash(MissingNode.getInstance()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("body must contain standard JSON values only");
    }

    private RequestHash hash(JsonNode body) {
        return hasher.hash("operation", Map.of(), body);
    }

    private static Map<String, String> linkedMap(
            String firstKey,
            String firstValue,
            String secondKey,
            String secondValue
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(firstKey, firstValue);
        values.put(secondKey, secondValue);
        return values;
    }
}
