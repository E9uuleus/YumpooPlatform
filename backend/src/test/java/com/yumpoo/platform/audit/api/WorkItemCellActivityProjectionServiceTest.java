package com.yumpoo.platform.audit.api;

import com.yumpoo.platform.audit.application.WorkItemCellActivityRepository;
import com.yumpoo.platform.audit.application.WorkItemCellActivityStoredEvent;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkItemCellActivityProjectionServiceTest {
    private static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("46000000-0000-4000-8000-000000000001");
    private static final UUID CONTENT = UUID.fromString("46000000-0000-4000-8000-000000000002");
    private static final UUID ITEM = UUID.fromString("46000000-0000-4000-8000-000000000003");
    private static final UUID ACTOR = UUID.fromString("46000000-0000-4000-8000-000000000004");
    private static final Instant CUTOVER = Instant.parse("2026-09-01T08:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkItemCellActivityRepository repository;
    private ActivityProjectionContextPort context;
    private WorkItemCellActivityProjectionService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkItemCellActivityRepository.class);
        context = mock(ActivityProjectionContextPort.class);
        when(repository.acceptedFrom()).thenReturn(CUTOVER);
        when(context.userDisplayName(COMPANY, ACTOR)).thenReturn(Optional.of("林晓"));
        when(context.content(COMPANY, PROJECT, CONTENT)).thenReturn(Optional.of(
                new ActivityProjectionContextPort.ContentReference(CONTENT, "需求")));
        when(context.priority(COMPANY, PROJECT, "HIGH")).thenReturn(Optional.of(
                new ActivityProjectionContextPort.LabelReference("HIGH", "高", "DARK_ORANGE")));
        service = new WorkItemCellActivityProjectionService(repository, context, objectMapper);
    }

    @Test
    void cutoverAndCreationProduceOnlyOneNameRow() {
        service.consume(event("workitem.work_item_created", CUTOVER.minusSeconds(1), base()));
        verify(repository, never()).append(any());

        service.consume(event("workitem.work_item_created", CUTOVER.plusSeconds(1), base()));
        ArgumentCaptor<WorkItemCellActivityStoredEvent> captor =
                ArgumentCaptor.forClass(WorkItemCellActivityStoredEvent.class);
        verify(repository).append(captor.capture());
        assertThat(captor.getValue().columnCode()).isEqualTo("WORK_ITEM_NAME");
        assertThat(captor.getValue().changeType()).isEqualTo("CREATED");
        assertThat(captor.getValue().contentDisplayName()).isEqualTo("需求");
    }

    @Test
    void multiFieldEventSplitsWhitelistAndKeepsPreviousSnapshots() {
        ObjectNode payload = base();
        payload.put("previousTitle", "旧标题");
        payload.put("previousPriority", "HIGH");
        payload.putNull("previousDueDate");
        payload.putNull("priority");
        payload.put("dueDate", "2026-09-08");
        payload.set("changedFields", objectMapper.createArrayNode().add("title")
                .add("priority").add("dueDate").add("description").add("notes")
                .add("timelineStartDate"));

        service.consume(event("workitem.work_item_fields_changed", CUTOVER.plusSeconds(1), payload));

        ArgumentCaptor<WorkItemCellActivityStoredEvent> captor =
                ArgumentCaptor.forClass(WorkItemCellActivityStoredEvent.class);
        verify(repository, times(3)).append(captor.capture());
        assertThat(captor.getAllValues()).extracting(WorkItemCellActivityStoredEvent::columnCode)
                .containsExactly("WORK_ITEM_NAME", "PRIORITY", "DUE_DATE");
        assertThat(captor.getAllValues().get(1).changeType()).isEqualTo("REMOVED");
        assertThat(captor.getAllValues().get(1).beforeValue().path("displayName").asText())
                .isEqualTo("高");
        assertThat(captor.getAllValues().get(2).changeType()).isEqualTo("ADDED");
    }

    @Test
    void assignmentUsesDedicatedEventAndMemberSnapshots() {
        UUID assignee = UUID.fromString("46000000-0000-4000-8000-000000000005");
        when(context.userDisplayName(COMPANY, assignee)).thenReturn(Optional.of("周衡"));
        ObjectNode payload = base();
        payload.putNull("previousAssigneeUserId");
        payload.put("assigneeUserId", assignee.toString());

        service.consume(event("workitem.work_item_assigned", CUTOVER.plusSeconds(1), payload));

        ArgumentCaptor<WorkItemCellActivityStoredEvent> captor =
                ArgumentCaptor.forClass(WorkItemCellActivityStoredEvent.class);
        verify(repository).append(captor.capture());
        assertThat(captor.getValue().columnCode()).isEqualTo("ASSIGNEE");
        assertThat(captor.getValue().changeType()).isEqualTo("ADDED");
        assertThat(captor.getValue().afterValue().path("displayName").asText()).isEqualTo("周衡");
    }

    @Test
    void deadlineProjectsTimeOnlyCombinedAndClearAsSingleDateValues() {
        ObjectNode payload = base();
        payload.put("previousDueDate", "2026-09-08").put("dueDate", "2026-09-08");
        payload.putNull("previousDueTime").put("dueTime", "18:05");
        payload.set("changedFields", objectMapper.createArrayNode().add("dueDate"));
        service.consume(event("workitem.work_item_fields_changed", 2, CUTOVER.plusSeconds(1), payload));
        payload.put("previousDueTime", "18:05").put("dueDate", "2026-09-09").put("dueTime", "09:30");
        service.consume(event("workitem.work_item_fields_changed", 2, CUTOVER.plusSeconds(2), payload));
        payload.put("previousDueDate", "2026-09-09").put("previousDueTime", "09:30");
        payload.putNull("dueDate").putNull("dueTime");
        service.consume(event("workitem.work_item_fields_changed", 2, CUTOVER.plusSeconds(3), payload));
        ArgumentCaptor<WorkItemCellActivityStoredEvent> captor = ArgumentCaptor.forClass(WorkItemCellActivityStoredEvent.class);
        verify(repository, times(3)).append(captor.capture());
        var changes = captor.getAllValues();
        assertThat(changes).extracting(WorkItemCellActivityStoredEvent::columnCode).containsOnly("DUE_DATE");
        assertThat(changes.get(0).beforeValue().path("displayName").asText()).isEqualTo("2026-09-08");
        assertThat(changes.get(0).afterValue().path("displayName").asText()).isEqualTo("2026-09-08 18:05");
        assertThat(changes.get(1).beforeValue().path("displayName").asText()).isEqualTo("2026-09-08 18:05");
        assertThat(changes.get(1).afterValue().path("displayName").asText()).isEqualTo("2026-09-09 09:30");
        assertThat(changes.get(2).changeType()).isEqualTo("REMOVED");
        assertThat(changes.get(2).beforeValue().path("displayName").asText()).isEqualTo("2026-09-09 09:30");
    }

    private ObjectNode base() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("workItemId", ITEM.toString());
        payload.put("projectId", PROJECT.toString());
        payload.put("contentId", CONTENT.toString());
        payload.put("title", "新标题");
        return payload;
    }

    private DomainEventEnvelope event(String type, Instant occurredAt, ObjectNode payload) {
        return event(type, 1, occurredAt, payload);
    }

    private DomainEventEnvelope event(String type, int version, Instant occurredAt, ObjectNode payload) {
        return new DomainEventEnvelope(UUID.randomUUID(), type, version, occurredAt, "WorkItem", ITEM,
                2, COMPANY, EventActor.user(ACTOR), "cell-activity-test", "cell-activity-test",
                null, payload);
    }
}
