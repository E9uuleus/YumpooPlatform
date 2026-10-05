package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.catalog.api.ProjectActiveMembershipQuery;
import com.yumpoo.platform.catalog.api.ProjectFactWriteGuard;
import com.yumpoo.platform.catalog.api.ProjectFactWriteSnapshot;
import com.yumpoo.platform.foundation.application.collaboration.CollaborationHtmlSanitizer;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.KanbanRank;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemStatusCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkItemCreationCoreTest {
    private final UUID company = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-01T10:00:00Z");
    private final CurrentActor actor = new CurrentActor(user, company, 0, Set.of());
    private final WorkItemRepository items = mock(WorkItemRepository.class);
    private final WorkItemRelationRepository relations = mock(WorkItemRelationRepository.class);
    private final ContentRepository contents = mock(ContentRepository.class);
    private final ProjectAccessSnapshotQuery access = mock(ProjectAccessSnapshotQuery.class);
    private final ProjectFactWriteGuard guard = mock(ProjectFactWriteGuard.class);
    private final ProjectActiveMembershipQuery members = mock(ProjectActiveMembershipQuery.class);
    private final WorkItemLabelRepository labels = mock(WorkItemLabelRepository.class);
    private final TransactionalEventPort events = mock(TransactionalEventPort.class);
    private final CollaborationHtmlSanitizer sanitizer = mock(CollaborationHtmlSanitizer.class);
    private final Content content = Content.initial(UUID.randomUUID(), company, project,
            "TASKS", "任务", "BRIGHT_GREEN", 10, user, now).markUsed(user, now);
    private WorkItemService service;

    @BeforeEach
    void setUp() {
        var idempotency = mock(IdempotentCommandExecutor.class);
        when(idempotency.execute(any(), any())).thenAnswer(call ->
                IdempotencyExecutionResult.executed(call.<Supplier<StoredCommandResult>>getArgument(1).get()));
        service = new WorkItemService(items, relations, contents, mock(WorkItemUpdateRepository.class),
                access, guard, members, labels, mock(MinimalUserSnapshotQuery.class), idempotency,
                events, JsonMapper.builder().build(), Clock.fixed(now, ZoneOffset.UTC),
                mock(TimeTrackingRepository.class), sanitizer,
                mock(com.yumpoo.platform.catalog.api.ProjectConnectionTargetQuery.class));
        when(access.findVisible(actor, project)).thenReturn(Optional.of(new ProjectAccessSnapshot(
                project, company, ProjectAccessSnapshot.ProjectLifecycle.ACTIVE,
                ProjectAccessSnapshot.ActorProjectAccess.MEMBER, 0, OptionalLong.of(0))));
        when(guard.lockForFactWrite(actor, project)).thenReturn(new ProjectFactWriteSnapshot(
                project, company, "P019", ProjectFactWriteSnapshot.ProjectLifecycle.ACTIVE,
                ProjectFactWriteSnapshot.ActorProjectAccess.MEMBER));
        when(contents.findLocator(company, content.id())).thenReturn(Optional.of(
                new ContentModels.ContentLocator(content.id(), project)));
        when(contents.find(company, project, content.id())).thenReturn(Optional.of(content));
        when(contents.lockForShare(company, project, content.id())).thenReturn(Optional.of(content));
        when(labels.statuses(company, project)).thenReturn(List.of(new WorkItemLabelModels.StatusLabel(
                "NOT_STARTED", "未开始", "GRAY", "TODO", 0, true, true, false)));
        when(labels.priorities(company, project)).thenReturn(List.of(new WorkItemLabelModels.PriorityLabel(
                "HIGH", "高", "ORANGE", 30, true, false)));
        when(items.nextSequence(company, project)).thenReturn(36L);
        when(items.insert(any())).thenReturn(true);
    }

    @Test
    void rootCreationPreservesNumberFieldsRankAndCreatedEvent() {
        when(members.findActiveMemberIds(company, project, List.of(user))).thenReturn(Set.of(user));
        when(sanitizer.sanitizeDescription("<p>正文</p>")).thenReturn("<p>正文</p>");
        LocalDate date = LocalDate.of(2026, 10, 3);
        var result = service.create(new WorkItemCommands.Create(actor, project, content.id(), "  回归事项  ",
                "HIGH", user, "<p>正文</p>", "备注", date, date, date, UUID.randomUUID(), hash(),
                new DueTimeChange(true, LocalTime.of(14, 30)))).result();
        var capture = ArgumentCaptor.forClass(WorkItem.class);
        verify(items).insert(capture.capture());
        WorkItem item = capture.getValue();
        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.etag()).isEqualTo("\"0\"");
        assertThat(item.itemNo()).isEqualTo("P019-36");
        assertThat(item.title()).isEqualTo("回归事项");
        assertThat(item.statusCode()).isEqualTo("NOT_STARTED");
        assertThat(item.priority()).isEqualTo("HIGH");
        assertThat(item.assigneeUserId()).isEqualTo(user);
        assertThat(item.reporterUserId()).isEqualTo(user);
        assertThat(item.description()).isEqualTo("<p>正文</p>");
        assertThat(item.notes()).isEqualTo("备注");
        assertThat(item.dueTime()).isEqualTo(LocalTime.of(14, 30));
        assertThat(item.rank()).isEqualTo(KanbanRank.between(null, null).orElseThrow());
        assertThat(item.projectSortKey()).isNotBlank();
        var order = inOrder(items);
        order.verify(items).lockRankLanes(company, project, List.of("NOT_STARTED"));
        order.verify(items).lockProjectOrder(company, project);
        order.verify(items).nextSequence(company, project);
        var event = ArgumentCaptor.forClass(EventDraft.class);
        verify(events).append(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("workitem.work_item_created");
        assertThat(event.getValue().eventVersion()).isEqualTo(2);
        assertThat(event.getValue().aggregateId()).isEqualTo(item.id());
        verifyNoInteractions(relations);
    }

    @Test
    void subitemCreationStillUsesSharedKernelAndCreatesOneParentRelation() {
        UUID parentId = UUID.randomUUID();
        WorkItem parent = WorkItem.create(parentId, company, project, content.id(), 1, "P019-1",
                "父事项", "NOT_STARTED", WorkItemStatusCategory.TODO, null, null, null, null,
                null, null, null, KanbanRank.between(null, null).orElseThrow(), user, now);
        when(items.findLocator(company, parentId)).thenReturn(Optional.of(
                new WorkItemModels.WorkItemLocator(parentId, project, content.id())));
        when(items.lock(company, project, content.id(), parentId)).thenReturn(Optional.of(parent));
        when(relations.insertParentChild(any())).thenReturn(true);
        service.createSubitem(new WorkItemCommands.CreateSubitem(actor, parentId, content.id(), "子事项",
                null, null, null, null, null, null, null, UUID.randomUUID(), hash(), DueTimeChange.unchanged()));
        var item = ArgumentCaptor.forClass(WorkItem.class);
        verify(items).insert(item.capture());
        assertThat(item.getValue().itemNo()).isEqualTo("P019-36");
        assertThat(item.getValue().statusCode()).isEqualTo("NOT_STARTED");
        assertThat(item.getValue().priority()).isNull();
        var relation = ArgumentCaptor.forClass(WorkItemRelationRepository.ParentChildRelation.class);
        verify(relations).insertParentChild(relation.capture());
        assertThat(relation.getValue().parentWorkItemId()).isEqualTo(parentId);
        assertThat(relation.getValue().childWorkItemId()).isEqualTo(item.getValue().id());
        var event = ArgumentCaptor.forClass(EventDraft.class);
        verify(events, times(2)).append(event.capture());
        assertThat(event.getAllValues()).extracting(EventDraft::eventType)
                .containsExactly("workitem.work_item_created", "workitem.work_item_relation_created");
    }

    @Test
    void invalidAssigneeStillFailsBeforeAllocatingNumberOrWritingEvents() {
        assertThatThrownBy(() -> service.create(new WorkItemCommands.Create(actor, project, content.id(),
                "事项", null, user, null, null, null, null, null, UUID.randomUUID(), hash(),
                DueTimeChange.unchanged()))).isInstanceOf(ApplicationException.class);
        verify(items, never()).nextSequence(any(), any());
        verify(items, never()).insert(any());
        verifyNoInteractions(events);
    }

    private static RequestHash hash() { return new RequestHash("0".repeat(64)); }
}
