package com.yumpoo.platform.audit.api;

import com.yumpoo.platform.audit.application.ActivityRepository;
import com.yumpoo.platform.audit.application.ActivityStoredEvent;
import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityProjectionServiceTest {
    private static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("44000000-0000-4000-8000-000000000001");
    private static final UUID ITEM = UUID.fromString("44000000-0000-4000-8000-000000000002");
    private static final UUID ACTOR = UUID.fromString("44000000-0000-4000-8000-000000000003");
    private static final Instant CUTOVER = Instant.parse("2026-08-30T08:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ActivityRepository repository;
    private ActivityProjectionContextPort context;
    private ActivityProjectionService service;

    @BeforeEach
    void setUp() {
        repository = mock(ActivityRepository.class);
        context = mock(ActivityProjectionContextPort.class);
        when(repository.acceptedFrom()).thenReturn(CUTOVER);
        when(context.userDisplayName(COMPANY, ACTOR)).thenReturn(Optional.of("林晓"));
        service = new ActivityProjectionService(repository, context, objectMapper);
    }

    @Test
    void acknowledgesPreCutoverEventsWithoutProjection() {
        service.consume(event("workitem.work_item_created", CUTOVER.minusSeconds(1), workItem()));
        verify(repository, never()).append(any());
    }

    @Test
    void storesOnlyWhitelistedWorkItemFactsAndActorSnapshot() {
        ObjectNode payload = workItem();
        payload.put("description", "不得落库的正文");
        payload.put("deleteReason", "不得落库的理由");

        service.consume(event("workitem.work_item_created", CUTOVER.plusSeconds(1), payload));

        ArgumentCaptor<ActivityStoredEvent> captor = ArgumentCaptor.forClass(ActivityStoredEvent.class);
        verify(repository).append(captor.capture());
        ActivityStoredEvent stored = captor.getValue();
        assertThat(stored.audienceType()).isEqualTo("PROJECT");
        assertThat(stored.scopeId()).isEqualTo(PROJECT);
        assertThat(stored.primaryWorkItemId()).isEqualTo(ITEM);
        assertThat(stored.actorDisplayName()).isEqualTo("林晓");
        assertThat(stored.safeParameters().toString())
                .contains("YMP-20", "投影验收")
                .doesNotContain("正文", "理由", "description", "deleteReason");
    }

    @Test
    void suppressesAssigneeOnlyFieldsChangedDuplicate() {
        ObjectNode payload = workItem();
        payload.set("changedFields", objectMapper.createArrayNode()
                .add("assigneeUserId").add("description").add("notes"));
        service.consume(event("workitem.work_item_fields_changed", CUTOVER.plusSeconds(1), payload));
        verify(repository, never()).append(any());
    }

    @Test
    void createsOneProjectionPerProjectForCrossProjectRelationWithoutCrossScopeIds() {
        UUID rightProject = UUID.fromString("44000000-0000-4000-8000-000000000004");
        UUID rightItem = UUID.fromString("44000000-0000-4000-8000-000000000005");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("relationId", UUID.randomUUID().toString());
        payload.put("relationType", "RELATES_TO");
        payload.put("leftWorkItemId", ITEM.toString());
        payload.put("rightWorkItemId", rightItem.toString());
        payload.put("leftProjectId", PROJECT.toString());
        payload.put("rightProjectId", rightProject.toString());

        service.consume(event("workitem.work_item_relation_created", CUTOVER.plusSeconds(1), payload));

        ArgumentCaptor<ActivityStoredEvent> captor = ArgumentCaptor.forClass(ActivityStoredEvent.class);
        verify(repository, org.mockito.Mockito.times(2)).append(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(stored -> {
            assertThat(stored.secondaryWorkItemId()).isNull();
            assertThat(stored.safeParameters().toString()).doesNotContain(rightItem.toString());
        });
        assertThat(captor.getAllValues()).extracting(ActivityStoredEvent::scopeId)
                .containsExactly(PROJECT, rightProject);
    }

    private ObjectNode workItem() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("workItemId", ITEM.toString());
        payload.put("projectId", PROJECT.toString());
        payload.put("contentId", UUID.randomUUID().toString());
        payload.put("itemNo", "YMP-20");
        payload.put("title", "投影验收");
        return payload;
    }

    private DomainEventEnvelope event(String type, Instant occurredAt, ObjectNode payload) {
        return new DomainEventEnvelope(UUID.randomUUID(), type, 1, occurredAt, "WorkItem", ITEM,
                1, COMPANY, EventActor.user(ACTOR), "m2-20-test", "m2-20-test", null, payload);
    }
}
