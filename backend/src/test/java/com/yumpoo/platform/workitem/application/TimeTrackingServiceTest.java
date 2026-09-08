package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.catalog.api.*;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.event.*;
import com.yumpoo.platform.foundation.application.idempotency.*;
import com.yumpoo.platform.identityaccess.api.*;
import com.yumpoo.platform.workitem.domain.WorkItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TimeTrackingServiceTest {
    @Test
    void editWithoutReasonEmitsPreviousRangeAndDeleteStillRequiresReason() {
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
        assertThatThrownBy(() -> service.command(actor, "delete", input, 0, UUID.randomUUID(), new RequestHash("b".repeat(64))))
                .isInstanceOf(ApplicationException.class);
        verify(timers, times(1)).save(any());
    }
}
