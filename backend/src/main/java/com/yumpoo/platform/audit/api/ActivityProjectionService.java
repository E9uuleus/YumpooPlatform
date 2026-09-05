package com.yumpoo.platform.audit.api;

import com.yumpoo.platform.audit.application.ActivityRepository;
import com.yumpoo.platform.audit.application.ActivityStoredEvent;
import com.yumpoo.platform.audit.api.ActivityProjectionContextPort.WorkItemReference;
import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventSubscription;
import com.yumpoo.platform.foundation.application.event.OutboxConsumerException;
import com.yumpoo.platform.foundation.application.event.OutboxEventConsumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class ActivityProjectionService implements OutboxEventConsumer {
    private static final Set<String> PRODUCT_EVENTS = Set.of(
            "catalog.product_created", "catalog.product_updated", "catalog.product_archived",
            "catalog.product_restored", "catalog.product_owner_reassigned");
    private static final Set<String> PROJECT_EVENTS = Set.of(
            "catalog.project_created", "catalog.project_updated", "catalog.project_activated",
            "catalog.project_archived", "catalog.project_reopened",
            "catalog.project_moved_to_workspace", "catalog.project_template_applied",
            "catalog.project_member_added", "catalog.project_member_removed",
            "catalog.project_owner_reassigned", "catalog.product_linked_to_project",
            "catalog.project_product_link_updated", "catalog.product_unlinked_from_project");
    private static final Set<String> CONTENT_EVENTS = Set.of(
            "workitem.content_created", "workitem.content_updated",
            "workitem.content_deleted", "workitem.content_archived", "workitem.content_restored");
    private static final Set<String> WORK_ITEM_EVENTS = Set.of(
            "workitem.work_item_created", "workitem.work_item_fields_changed",
            "workitem.work_item_assigned", "workitem.work_item_unassigned",
            "workitem.work_item_status_changed", "workitem.work_item_rank_changed",
            "workitem.work_item_deleted", "workitem.work_item_restored",
            "workitem.work_item_relation_created", "workitem.work_item_relation_deleted",
            "workitem.work_item_parent_changed", "workitem.work_item_update_published",
            "workitem.work_item_update_edited", "workitem.work_item_update_deleted",
            "workitem.work_item_update_pin_changed");
    private static final Set<String> ATTACHMENT_EVENTS = Set.of(
            "filestorage.attachment_available", "filestorage.attachment_deleted");
    private static final Set<String> V2_EVENTS = Set.of(
            "workitem.content_created", "workitem.content_updated", "workitem.content_deleted",
            "workitem.work_item_created", "workitem.work_item_fields_changed",
            "workitem.work_item_status_changed", "workitem.work_item_deleted",
            "workitem.work_item_restored", "workitem.work_item_update_published",
            "workitem.work_item_update_edited", "workitem.work_item_update_deleted");

    private final ActivityRepository repository;
    private final ActivityProjectionContextPort context;
    private final ObjectMapper objectMapper;

    public ActivityProjectionService(ActivityRepository repository,
            ActivityProjectionContextPort context, ObjectMapper objectMapper) {
        this.repository = repository;
        this.context = context;
        this.objectMapper = objectMapper;
    }

    @Override
    public String consumerName() {
        return "audit-activity-v1";
    }

    @Override
    public Set<EventSubscription> subscriptions() {
        LinkedHashSet<EventSubscription> subscriptions = new LinkedHashSet<>();
        allEvents().forEach(type -> subscriptions.add(new EventSubscription(type, 1)));
        V2_EVENTS.forEach(type -> subscriptions.add(new EventSubscription(type, 2)));
        return Set.copyOf(subscriptions);
    }

    @Override
    public void consume(DomainEventEnvelope event) {
        if (event.occurredAt().isBefore(repository.acceptedFrom())) return;
        try {
            if (PRODUCT_EVENTS.contains(event.eventType())) appendProduct(event);
            else if (PROJECT_EVENTS.contains(event.eventType())) appendProject(event);
            else if (CONTENT_EVENTS.contains(event.eventType())) appendContent(event);
            else if (WORK_ITEM_EVENTS.contains(event.eventType())) appendWorkItem(event);
            else if (ATTACHMENT_EVENTS.contains(event.eventType())) appendAttachment(event);
            else throw invalid();
        } catch (OutboxConsumerException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private void appendProduct(DomainEventEnvelope event) {
        JsonNode payload = event.payload();
        UUID productId = uuid(payload, "productId");
        String ref = join(text(payload, "code"), text(payload, "name"));
        ObjectNode safe = safeRef(ref);
        if (event.eventType().endsWith("owner_reassigned")) {
            safe.put("memberDisplayName", user(event, uuid(payload, "newOwnerUserId")));
        }
        append(event, ActivityAudienceType.PRODUCT, productId, "PRODUCT", productId, ref,
                template(event.eventType()), safe, null, null);
    }

    private void appendProject(DomainEventEnvelope event) {
        JsonNode payload = event.payload();
        UUID projectId = uuid(payload, "projectId");
        String entityType = "PROJECT";
        UUID entityId = projectId;
        String ref = optionalJoin(payload, "code", "name");
        ObjectNode safe = safeRef(ref);
        if (event.eventType().contains("member_")) {
            entityType = "PROJECT_MEMBER";
            entityId = uuid(payload, "membershipId");
            safe.put("memberDisplayName", user(event, uuid(payload, "userId")));
        } else if (event.eventType().endsWith("owner_reassigned")) {
            safe.put("memberDisplayName", user(event, uuid(payload, "newOwnerUserId")));
        } else if (event.eventType().contains("product_link")
                || event.eventType().contains("product_unlinked")) {
            entityType = "PRODUCT";
            entityId = uuid(payload, "productId");
            safe.put("relationType", text(payload, "relationType"));
        } else if (event.eventType().endsWith("template_applied")) {
            safe.put("templateKey", text(payload, "templateKey"));
            safe.put("initializedContentCount", integer(payload, "initializedContentCount"));
        }
        append(event, ActivityAudienceType.PROJECT, projectId, entityType, entityId, ref,
                template(event.eventType()), safe, null, null);
    }

    private void appendContent(DomainEventEnvelope event) {
        JsonNode payload = event.payload();
        UUID contentId = uuid(payload, "contentId");
        String ref = join(text(payload, "code"), text(payload, "name"));
        ObjectNode safe = safeRef(ref);
        if (event.eventVersion() == 1) safe.put("workItemType", text(payload, "workItemType"));
        else {
            safe.put("colorToken", text(payload, "colorToken"));
            safe.put("active", payload.path("active").asBoolean());
        }
        append(event, ActivityAudienceType.PROJECT, uuid(payload, "projectId"), "CONTENT",
                contentId, ref, template(event.eventType()), safe, null, null);
    }

    private void appendWorkItem(DomainEventEnvelope event) {
        JsonNode payload = event.payload();
        if (event.eventType().contains("work_item_relation_")
                || event.eventType().endsWith("parent_changed")) {
            appendRelation(event);
            return;
        }
        UUID workItemId = uuid(payload, "workItemId");
        UUID projectId = uuid(payload, "projectId");
        String ref = optionalJoin(payload, "itemNo", "title");
        ObjectNode safe = safeRef(ref);
        String type = event.eventType();
        if (type.endsWith("fields_changed")) {
            ArrayNode fields = array(payload, "changedFields");
            ArrayNode kept = objectMapper.createArrayNode();
            boolean contentChanged = false;
            fields.forEach(field -> {
                if (field.isTextual() && !"assigneeUserId".equals(field.textValue())
                        && !"description".equals(field.textValue())
                        && !"notes".equals(field.textValue())) kept.add(field.textValue());
            });
            if (kept.isEmpty()) return;
            safe.set("changedFields", kept);
            for (JsonNode field : kept) {
                if ("contentId".equals(field.textValue())) {
                    contentChanged = true;
                    break;
                }
            }
            if (contentChanged) {
                safe.put("previousContentName", text(payload, "previousContentName"));
                safe.put("contentName", text(payload, "contentName"));
                safe.put("previousContentColorToken", text(payload, "previousContentColorToken"));
                safe.put("contentColorToken", text(payload, "contentColorToken"));
            }
        } else if (type.equals("workitem.work_item_assigned")) {
            safe.put("memberDisplayName", user(event, uuid(payload, "assigneeUserId")));
        } else if (type.endsWith("status_changed")) {
            safe.put("fromStatus", text(payload, "fromStatus"));
            safe.put("toStatus", text(payload, "toStatus"));
        } else if (type.endsWith("rank_changed")) {
            safe.put("placement", text(payload, "placement"));
        } else if (type.endsWith("update_published")) {
            safe.put("mentionCount", array(payload, "mentionedUserIds").size());
        } else if (type.endsWith("update_edited")) {
            safe.put("mentionCount", array(payload, "mentionedUserIds").size());
            safe.put("addedMentionCount", array(payload, "addedMentionedUserIds").size());
            safe.put("removedMentionCount", array(payload, "removedMentionedUserIds").size());
        }
        if (type.endsWith("update_pin_changed")) safe.put("pinned", payload.path("pinned").asBoolean());
        boolean update = type.contains("work_item_update_");
        UUID entityId = update ? uuid(payload, "updateId") : workItemId;
        append(event, ActivityAudienceType.PROJECT, projectId,
                update ? "WORK_ITEM_UPDATE" : "WORK_ITEM", entityId, ref,
                template(type), safe, workItemId, null);
    }

    private void appendRelation(DomainEventEnvelope event) {
        JsonNode payload = event.payload();
        UUID relationId = uuid(payload, "relationId");
        UUID leftId = uuid(payload, "leftWorkItemId");
        UUID rightId = uuid(payload, "rightWorkItemId");
        UUID leftProject = uuid(payload, "leftProjectId");
        UUID rightProject = uuid(payload, "rightProjectId");
        ObjectNode leftSafe = objectMapper.createObjectNode();
        leftSafe.put("relationType", text(payload, "relationType"));
        context.workItem(event.companyId(), leftId)
                .ifPresent(item -> leftSafe.put("entityRef", item.displayRef()));
        append(event, ActivityAudienceType.PROJECT, leftProject, "WORK_ITEM_RELATION",
                relationId, leftSafe.path("entityRef").asText(null),
                template(event.eventType()), leftSafe, leftId,
                leftProject.equals(rightProject) ? rightId : null);
        if (!leftProject.equals(rightProject)) {
            ObjectNode rightSafe = objectMapper.createObjectNode();
            rightSafe.put("relationType", text(payload, "relationType"));
            context.workItem(event.companyId(), rightId)
                    .ifPresent(item -> rightSafe.put("entityRef", item.displayRef()));
            append(event, ActivityAudienceType.PROJECT, rightProject, "WORK_ITEM_RELATION",
                    relationId, rightSafe.path("entityRef").asText(null),
                    template(event.eventType()), rightSafe, rightId, null);
        }
    }

    private void appendAttachment(DomainEventEnvelope event) {
        JsonNode payload = event.payload();
        UUID attachmentId = uuid(payload, "attachmentId");
        UUID workItemId = optionalUuid(payload, "workItemId").orElseGet(() -> {
            String ownerType = text(payload, "ownerType");
            if ("WORK_ITEM".equals(ownerType)) return uuid(payload, "ownerId");
            return context.attachmentOwnerWorkItem(event.companyId(), ownerType,
                            uuid(payload, "ownerId"))
                    .map(WorkItemReference::id).orElse(null);
        });
        ObjectNode safe = safeRef(text(payload, "fileName"));
        safe.put("sizeBytes", number(payload, "sizeBytes"));
        if (event.eventType().endsWith("available")) {
            optionalUuid(payload, "uploadedByUserId")
                    .ifPresent(id -> safe.put("memberDisplayName", user(event, id)));
        }
        append(event, ActivityAudienceType.PROJECT, uuid(payload, "projectId"), "ATTACHMENT",
                attachmentId, text(payload, "fileName"), template(event.eventType()), safe,
                workItemId, null);
    }

    private void append(DomainEventEnvelope source, ActivityAudienceType audience, UUID scopeId,
            String entityType, UUID entityId, String entityRef, String template,
            ObjectNode safe, UUID primaryWorkItemId, UUID secondaryWorkItemId) {
        String actorType = source.actor().type().name();
        UUID actorUserId = source.actor().userId();
        String actorSystemCode = source.actor().systemCode();
        String display = actorUserId == null ? systemDisplay(actorSystemCode)
                : context.userDisplayName(source.companyId(), actorUserId).orElse("已停用成员");
        repository.append(new ActivityStoredEvent(UUID.randomUUID(), source.eventId(), audience.name(),
                source.companyId(), scopeId, entityType, entityId, truncate(entityRef, 320),
                source.eventType(), actorType, actorUserId, actorSystemCode, display,
                source.occurredAt(), template, safe, source.aggregateVersion(),
                source.requestId(), source.correlationId(), primaryWorkItemId,
                secondaryWorkItemId));
    }

    private ObjectNode safeRef(String ref) {
        ObjectNode node = objectMapper.createObjectNode();
        if (ref != null && !ref.isBlank()) node.put("entityRef", truncate(ref, 320));
        return node;
    }

    private String user(DomainEventEnvelope event, UUID userId) {
        return context.userDisplayName(event.companyId(), userId).orElse("已停用成员");
    }

    private static Set<String> allEvents() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.addAll(PRODUCT_EVENTS); result.addAll(PROJECT_EVENTS); result.addAll(CONTENT_EVENTS);
        result.addAll(WORK_ITEM_EVENTS); result.addAll(ATTACHMENT_EVENTS);
        return result;
    }

    private static String template(String eventType) {
        return eventType.substring(eventType.indexOf('.') + 1).toUpperCase();
    }

    private static UUID uuid(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        try { return UUID.fromString(value.textValue()); }
        catch (IllegalArgumentException failure) { throw invalid(); }
    }

    private static Optional<UUID> optionalUuid(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) return Optional.empty();
        return Optional.of(uuid(payload, field));
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }

    private static long number(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber()) throw invalid();
        return value.longValue();
    }

    private static int integer(JsonNode payload, String field) {
        return Math.toIntExact(number(payload, field));
    }

    private static ArrayNode array(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (!(value instanceof ArrayNode array)) throw invalid();
        return array;
    }

    private static String optionalJoin(JsonNode payload, String first, String second) {
        JsonNode left = payload.get(first); JsonNode right = payload.get(second);
        String one = left != null && left.isTextual() ? left.textValue() : null;
        String two = right != null && right.isTextual() ? right.textValue() : null;
        if (one == null && two == null) return null;
        return join(one, two);
    }

    private static String join(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank()) return first;
        return first + " " + second;
    }

    private static String systemDisplay(String code) {
        return switch (code == null ? "" : code) {
            case "ATTACHMENT_SCANNER" -> "附件安全扫描服务";
            default -> "系统";
        };
    }

    private static String truncate(String value, int maximum) {
        return value == null || value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static OutboxConsumerException invalid() {
        return new OutboxConsumerException("ACTIVITY_INVALID_V1_PAYLOAD", false);
    }
}
