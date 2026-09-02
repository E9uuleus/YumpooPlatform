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

    private ObjectNode base() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("workItemId", ITEM.toString());
        payload.put("projectId", PROJECT.toString());
        payload.put("contentId", CONTENT.toString());
        payload.put("title", "新标题");
        return payload;
    }

    private DomainEventEnvelope event(String type, Instant occurredAt, ObjectNode payload) {
        return new DomainEventEnvelope(UUID.randomUUID(), type, 1, occurredAt, "WorkItem", ITEM,
                2, COMPANY, EventActor.user(ACTOR), "cell-activity-test", "cell-activity-test",
                null, payload);
    }
}
