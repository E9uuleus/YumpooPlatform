package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.catalog.api.*;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.event.*;
import com.yumpoo.platform.foundation.application.idempotency.*;
import com.yumpoo.platform.identityaccess.api.*;
import com.yumpoo.platform.workitem.domain.WorkItem;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TimeTrackingServiceTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "旧客户端原因"})
    void editAndDeleteWithoutReasonKeepSnapshotsAndAudit(String reason) {
        UUID company = UUID.randomUUID(), user = UUID.randomUUID(), item = UUID.randomUUID();
        UUID project = UUID.randomUUID(), content = UUID.randomUUID(), sessionId = UUID.randomUUID();
        var actor = new CurrentActor(user, company, 0, Set.of());
        var timers = mock(TimeTrackingRepository.class);
        var items = mock(WorkItemRepository.class);
        var access = mock(ProjectAccessSnapshotQuery.class);
        var guard = mock(ProjectFactWriteGuard.class);
        var users = mock(MinimalUserSnapshotQuery.class);
        var executor = mock(IdempotentCommandExecutor.class);
        var events = mock(TransactionalEventPort.class);
        var audits = mock(SecurityAuditAppendPort.class);
        var json = new ObjectMapper();
        var before = new TimeTrackingModels.Session(sessionId, company, project, item, user,
                Instant.parse("2026-09-01T01:00:00Z"), Instant.parse("2026-09-01T02:00:00Z"), "MANUAL", 0, null, null);
        when(items.findLocator(company, item)).thenReturn(Optional.of(new WorkItemModels.WorkItemLocator(item, project, content)));
        when(items.lockProjectItem(company, project, item)).thenReturn(Optional.of(mock(WorkItem.class)));
        when(access.findVisible(actor, project)).thenReturn(Optional.of(mock(ProjectAccessSnapshot.class)));
        when(guard.lockForFactWrite(actor, project)).thenReturn(new ProjectFactWriteSnapshot(project, company, "TEST",
                ProjectFactWriteSnapshot.ProjectLifecycle.ACTIVE, ProjectFactWriteSnapshot.ActorProjectAccess.MEMBER, "TEST", 1));
        when(timers.find(company, sessionId)).thenReturn(Optional.of(before));
        when(executor.execute(any(), any())).thenAnswer(call -> {
            Supplier<StoredCommandResult> action = call.getArgument(1);
            return IdempotencyExecutionResult.executed(action.get());
        });
        var service = new TimeTrackingService(timers, items, access, guard, users, executor, events, audits, json,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC));
        var input = new TimeTrackingService.Input(item, sessionId, before.startedAt(), before.stoppedAt().plusSeconds(1800), null);
        var result = service.command(actor, "edit", input, 0, UUID.randomUUID(), new RequestHash("a".repeat(64)));
        assertThat(json.readTree(result.responseJson()).path("durationMs").asLong()).isEqualTo(5400000);
        var captured = ArgumentCaptor.forClass(EventDraft.class);
        verify(events).append(captured.capture());
        assertThat(captured.getValue().eventVersion()).isEqualTo(2);
        assertThat(captured.getValue().payload().path("previousStoppedAt").asText()).isEqualTo("2026-09-01T02:00:00Z");
        assertThat(captured.getValue().payload().path("contentId").asText()).isEqualTo(content.toString());
        verify(audits).append(any());
        assertThatThrownBy(() -> service.command(actor, "delete", input, 1, UUID.randomUUID(), new RequestHash("c".repeat(64))))
                .isInstanceOf(ApplicationException.class);
        var deleteInput = new TimeTrackingService.Input(item, sessionId, null, null, reason);
        var deleted = service.command(actor, "delete", deleteInput, 0, UUID.randomUUID(), new RequestHash("b".repeat(64)));
        assertThat(json.readTree(deleted.responseJson()).path("deleted").asBoolean()).isTrue();
        verify(events, times(2)).append(captured.capture());
        var deletion = captured.getValue();
        assertThat(deletion.eventType()).isEqualTo("workitem.time_tracking_deleted");
        assertThat(deletion.eventVersion()).isEqualTo(2);
        assertThat(deletion.actor().userId()).isEqualTo(user);
        assertThat(deletion.payload().path("contentId").asText()).isEqualTo(content.toString());
        assertThat(deletion.payload().path("stoppedAt").asText()).isEqualTo(before.stoppedAt().toString());
        var audit = ArgumentCaptor.forClass(com.yumpoo.platform.audit.api.SecurityAuditDraft.class);
        verify(audits, times(2)).append(audit.capture());
        assertThat(audit.getValue().occurredAt()).isEqualTo(Instant.parse("2026-09-07T00:00:00Z"));
        assertThat(audit.getValue().reasonReference()).isEqualTo(reason == null || reason.isBlank() ? null : reason);
        verify(timers, times(2)).save(any());
    }
}
