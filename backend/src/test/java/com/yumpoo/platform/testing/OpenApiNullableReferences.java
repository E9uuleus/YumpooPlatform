package com.yumpoo.platform.testing;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class OpenApiNullableReferences {

    private static final Set<String> ALLOWED_KEYS =
            Set.of("type", "nullable", "allOf", "description", "readOnly", "deprecated", "example");

    private OpenApiNullableReferences() {}

    static void normalize(ObjectNode root) {
        List<String> problems = new ArrayList<>();
        visit(root, JsonPointer.empty(), problems);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("无法规范化 OpenAPI 可空引用：\n" + String.join("\n", problems));
        }
    }

    private static void visit(JsonNode node, JsonPointer pointer, List<String> problems) {
        if (node instanceof ObjectNode object) {
            object.properties()
                    .forEach(entry -> visit(entry.getValue(), pointer.appendProperty(entry.getKey()), problems));
            if (BooleanNode.TRUE.equals(object.get("nullable")) && object.has("allOf")) {
                normalizeReference(object, pointer, problems);
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                visit(array.get(index), pointer.appendIndex(index), problems);
            }
        }
    }

    private static void normalizeReference(ObjectNode object, JsonPointer pointer, List<String> problems) {
        int previousCount = problems.size();
        reportExtraKeys(object, pointer, ALLOWED_KEYS, problems);
        if (!object.has("type")) {
            problems.add(pointer + "：缺少 type");
        }

        JsonNode allOf = object.get("allOf");
        JsonPointer allOfPointer = pointer.appendProperty("allOf");
        if (!allOf.isArray() || allOf.size() != 1) {
            problems.add(allOfPointer + "：必须是只含一个 $ref 的数组");
        }
        if (allOf.isArray()) {
            for (int index = 0; index < allOf.size(); index++) {
                JsonPointer itemPointer = allOfPointer.appendIndex(index);
                JsonNode item = allOf.get(index);
                if (!item.isObject() || !item.has("$ref")) {
                    problems.add(itemPointer + "：必须是含 $ref 的对象");
                }
                if (item instanceof ObjectNode member) {
                    reportExtraKeys(member, itemPointer, Set.of("$ref"), problems);
                }
            }
        }
        if (problems.size() != previousCount) {
            return;
        }

        JsonNode type = object.remove("type");
        object.remove("nullable");
        object.remove("allOf");
        ArrayNode anyOf = object.putArray("anyOf");
        anyOf.add(allOf.get(0));
        ObjectNode nullOnly = anyOf.addObject();
        nullOnly.set("type", type);
        nullOnly.put("nullable", true);
        nullOnly.putArray("enum").addNull();
    }

    private static void reportExtraKeys(
            ObjectNode node, JsonPointer pointer, Set<String> allowed, List<String> problems) {
        List<String> extraKeys = node.properties().stream()
                .map(Map.Entry::getKey)
                .filter(key -> !allowed.contains(key))
                .sorted()
                .toList();
        if (!extraKeys.isEmpty()) {
            problems.add(pointer + "：多余键 " + extraKeys);
        }
    }
}
