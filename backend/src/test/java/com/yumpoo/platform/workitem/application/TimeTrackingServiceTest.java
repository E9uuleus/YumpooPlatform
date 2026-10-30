package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.catalog.api.*;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.*;
import com.yumpoo.platform.foundation.application.idempotency.*;
import com.yumpoo.platform.identityaccess.api.*;
import com.yumpoo.platform.workitem.domain.WorkItem;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
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
        var deletionQuery = mock(ProjectDeletionQuery.class);
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
                ProjectFactWriteSnapshot.ProjectLifecycle.ACTIVE, ProjectFactWriteSnapshot.ActorProjectAccess.MEMBER));
        when(timers.find(company, sessionId)).thenReturn(Optional.of(before));
        when(executor.execute(any(), any())).thenAnswer(call -> {
            Supplier<StoredCommandResult> action = call.getArgument(1);
            return IdempotencyExecutionResult.executed(action.get());
        });
        var service = new TimeTrackingService(timers, mock(TimeShareMaintainer.class), items, access, mock(ProjectConnectionTargetQuery.class), guard, deletionQuery, users, executor, events, audits, json,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC));
        var input = new TimeTrackingService.Input(item, sessionId, before.startedAt(), before.stoppedAt().plusSeconds(1800), null, null, null, null, null, null);
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
        var deleteInput = new TimeTrackingService.Input(item, sessionId, null, null, reason, null, null, null, null, null);
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

    @ParameterizedTest
    @ValueSource(strings = {"ARCHIVED", "ACTIVE"})
    void ownTimerCanStopWithoutMembershipOrAnActiveProject(String lifecycle) {
        var fixture = new TimerFixture(false);
        when(fixture.deletion.lockForProjection(fixture.company, fixture.oldProject))
                .thenReturn(Optional.of(fixture.project(fixture.oldProject, lifecycle, null)));
        fixture.command("stop");

        var saved = ArgumentCaptor.forClass(TimeTrackingModels.Session.class);
        verify(fixture.timers).save(saved.capture());
        assertThat(saved.getValue().projectId()).isEqualTo(fixture.oldProject);
        assertThat(saved.getValue().stoppedAt()).isEqualTo(TimerFixture.NOW);
        verify(fixture.timers).advanceProjects(fixture.company, List.of(fixture.oldProject));
        verify(fixture.events).append(any());
        verify(fixture.access, never()).findVisible(fixture.actor, fixture.oldProject);
        verifyNoInteractions(fixture.guard, fixture.items);
    }

    @ParameterizedTest
    @CsvSource({"stop,true", "stop,false", "switch,true", "switch,false", "start,true", "start,false"})
    void missingOrPurgingProjectRejectsTimerWrites(String action, boolean purging) {
        var fixture = new TimerFixture(false);
        UUID rejectedProject = action.equals("start") ? fixture.targetProject : fixture.oldProject;
        if (action.equals("start")) when(fixture.timers.running(fixture.company, fixture.user)).thenReturn(List.of());
        when(fixture.deletion.lockForProjection(fixture.company, rejectedProject)).thenReturn(purging
                ? Optional.of(fixture.project(rejectedProject, "ARCHIVED", TimerFixture.NOW)) : Optional.empty());

        assertThatThrownBy(() -> fixture.command(action)).isInstanceOfSatisfying(ApplicationException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.RESOURCE_NOT_FOUND));
        verify(fixture.timers, never()).stateVersion(fixture.company, fixture.user, true);
        fixture.verifyNoWrites();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void switchLocksBothProjectsInUuidOrderBeforeTimerStateAndItem(boolean reverseProjects) {
        var fixture = new TimerFixture(reverseProjects);
        fixture.command("switch");
        var ordered = inOrder(fixture.deletion, fixture.guard, fixture.timers, fixture.items);
        var projectIds = java.util.stream.Stream.of(fixture.oldProject, fixture.targetProject).sorted().toList();
        for (UUID project : projectIds) ordered.verify(fixture.deletion).lockForProjection(fixture.company, project);
        ordered.verify(fixture.guard).lockForFactWrite(fixture.actor, fixture.targetProject);
        ordered.verify(fixture.timers).stateVersion(fixture.company, fixture.user, true);
        ordered.verify(fixture.items).lockProjectItem(fixture.company, fixture.targetProject, fixture.targetItem);
        verify(fixture.timers, times(2)).save(any());
        for(UUID project:projectIds) ordered.verify(fixture.timers).advanceProjects(fixture.company,List.of(project));
        verify(fixture.events, times(2)).append(any());
    }

    @Test
    void changedRunningSnapshotCannotCloseAnUnprotectedThirdProject() {
        var fixture = new TimerFixture(false);
        UUID thirdProject = UUID.randomUUID();
        var changed = new TimeTrackingModels.Session(UUID.randomUUID(), fixture.company, thirdProject,
                UUID.randomUUID(), fixture.user, fixture.session.startedAt(), null, "TIMER", 0, null, null);
        when(fixture.timers.running(fixture.company, fixture.user)).thenReturn(List.of(fixture.session), List.of(changed));
        var changedInput = new TimeTrackingService.Input(fixture.targetItem, changed.id(), null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> fixture.service.command(fixture.actor, "switch", changedInput, 1,
                UUID.randomUUID(), new RequestHash("b".repeat(64)))).isInstanceOfSatisfying(ApplicationException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.INVALID_STATE_TRANSITION));
        verify(fixture.deletion, never()).lockForProjection(fixture.company, thirdProject);
        fixture.verifyNoWrites();
    }

    @Test
    void startRechecksArchiveStateAfterItsInitialTargetRead() {
        var fixture = new TimerFixture(false);
        when(fixture.timers.running(fixture.company, fixture.user)).thenReturn(List.of());
        when(fixture.guard.lockForFactWrite(fixture.actor, fixture.targetProject)).thenReturn(new ProjectFactWriteSnapshot(
                fixture.targetProject, fixture.company, "TEST", ProjectFactWriteSnapshot.ProjectLifecycle.ARCHIVED,
                ProjectFactWriteSnapshot.ActorProjectAccess.MEMBER));
        assertThatThrownBy(() -> fixture.command("start")).isInstanceOfSatisfying(ApplicationException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.INVALID_STATE_TRANSITION));
        verify(fixture.timers, never()).stateVersion(fixture.company, fixture.user, true);
        fixture.verifyNoWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "switch"})
    void disabledTimerRejectsStartAndSwitchWithoutWrites(String action) {
        var fixture = new TimerFixture(false);
        if (action.equals("start")) when(fixture.timers.running(fixture.company, fixture.user)).thenReturn(List.of());
        when(fixture.timers.preferences(fixture.company, fixture.user))
                .thenReturn(Optional.of(new TimeTrackingModels.TimeTrackingPreferences(false, 540, 1080, false)));
        assertThatThrownBy(() -> fixture.command(action)).isInstanceOfSatisfying(ApplicationException.class, failure -> {
            assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.INVALID_STATE_TRANSITION);
            assertThat(failure.reason()).isEqualTo("TIMER_DISABLED");
        });
        fixture.verifyNoWrites();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disablingTimerStopsAllRunningSessionsAfterProjectAndStateLocks(boolean reverseProjects) {
        var fixture = new TimerFixture(reverseProjects);
        var second = new TimeTrackingModels.Session(UUID.randomUUID(), fixture.company, fixture.targetProject, fixture.targetItem,
                fixture.user, TimerFixture.NOW.minusSeconds(600), null, "TIMER", 0, null, null);
        when(fixture.timers.running(fixture.company, fixture.user))
                .thenReturn(List.of(fixture.session, second), List.of(fixture.session, second), List.of());
        var disabled = new TimeTrackingModels.TimeTrackingPreferences(false, 480, 1020, false);
        fixture.service.updatePreferences(fixture.actor, new TimeTrackingModels.TimeTrackingPreferencesCommand(false, 480, 1020, null),
                1, UUID.randomUUID(), new RequestHash("d".repeat(64)));

        var ordered = inOrder(fixture.deletion, fixture.timers);
        for (UUID project : java.util.stream.Stream.of(fixture.oldProject, fixture.targetProject).sorted().toList())
            ordered.verify(fixture.deletion).lockForProjection(fixture.company, project);
        ordered.verify(fixture.timers).stateVersion(fixture.company, fixture.user, true);
        ordered.verify(fixture.timers).savePreferences(fixture.company, fixture.user, disabled);
        ordered.verify(fixture.timers).advanceState(fixture.company, fixture.user);
        var saved = ArgumentCaptor.forClass(TimeTrackingModels.Session.class);
        verify(fixture.timers, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(session -> assertThat(session.stoppedAt()).isEqualTo(TimerFixture.NOW));
        verify(fixture.timers, times(1)).advanceState(fixture.company, fixture.user);
        verify(fixture.events, times(2)).append(any());
        verifyNoInteractions(fixture.guard, fixture.items);
    }

    @Test
    void unchangedPreferencesKeepTheTimerStateVersion() {
        var fixture = new TimerFixture(false);
        fixture.service.updatePreferences(fixture.actor, new TimeTrackingModels.TimeTrackingPreferencesCommand(true, 540, 1080, null),
                1, UUID.randomUUID(), new RequestHash("e".repeat(64)));
        verify(fixture.deletion, never()).lockForProjection(any(), any());
        verify(fixture.timers, never()).savePreferences(any(), any(), any());
        fixture.verifyNoWrites();
    }

    @ParameterizedTest
    @CsvSource({",540,1080", "true,,1080", "true,540,", "true,600,540", "true,-1,1080", "true,540,1441", "true,1440,1440"})
    void invalidPreferencesAreRejectedBeforeLocking(Boolean enabled, Integer start, Integer end) {
        var fixture = new TimerFixture(false);
        assertThatThrownBy(() -> fixture.service.updatePreferences(fixture.actor,
                new TimeTrackingModels.TimeTrackingPreferencesCommand(enabled, start, end, null), 1, UUID.randomUUID(),
                new RequestHash("f".repeat(64)))).isInstanceOfSatisfying(ApplicationException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.VALIDATION_FAILED));
        verify(fixture.timers, never()).stateVersion(fixture.company, fixture.user, true);
        fixture.verifyNoWrites();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reassignLocksBothProjectsThenStateThenItemsAndDeletesBeforeAdding(boolean reverseProjects) {
        var fixture = new TimerFixture(reverseProjects);
        var stopped = fixture.stoppedSession(3);
        var result = fixture.service.reassign(fixture.actor, stopped.id(), new TimeTrackingModels.TimeSessionReassignCommand(fixture.targetItem, null, null), 3, UUID.randomUUID(),
                new RequestHash("1".repeat(64)));

        var body = new ObjectMapper().readTree(result.responseJson());
        assertThat(body.path("deletedCount").asInt()).isOne();
        assertThat(body.path("createdCount").asInt()).isOne();
        var ordered = inOrder(fixture.guard, fixture.timers, fixture.items);
        for (UUID project : java.util.stream.Stream.of(fixture.oldProject, fixture.targetProject).sorted().toList())
            ordered.verify(fixture.guard).lockForFactWrite(fixture.actor, project);
        ordered.verify(fixture.timers).stateVersion(fixture.company, fixture.user, true);
        for (UUID item : java.util.stream.Stream.of(stopped.workItemId(), fixture.targetItem).sorted().toList())
            ordered.verify(fixture.items).lockProjectItem(eq(fixture.company), any(), eq(item));
        var saved = ArgumentCaptor.forClass(TimeTrackingModels.Session.class);
        verify(fixture.timers, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).id()).isEqualTo(stopped.id());
        assertThat(saved.getAllValues().get(0).deletedAt()).isEqualTo(TimerFixture.NOW);
        assertThat(saved.getAllValues().get(1).workItemId()).isEqualTo(fixture.targetItem);
        assertThat(saved.getAllValues().get(1).source()).isEqualTo("MANUAL");
        assertThat(saved.getAllValues().get(1).startedAt()).isEqualTo(stopped.startedAt());
        assertThat(saved.getAllValues().get(1).stoppedAt()).isEqualTo(stopped.stoppedAt());
        var events = ArgumentCaptor.forClass(EventDraft.class);
        verify(fixture.events, times(2)).append(events.capture());
        assertThat(events.getAllValues()).extracting(EventDraft::eventType)
                .containsExactly("workitem.time_tracking_deleted", "workitem.time_tracking_added");
    }

    @Test
    void reassignRejectsStaleRunningAndUnchangedSessionsWithoutWrites() {
        var fixture = new TimerFixture(false);
        var stopped = fixture.stoppedSession(3);
        assertThatThrownBy(() -> fixture.service.reassign(fixture.actor, stopped.id(), new TimeTrackingModels.TimeSessionReassignCommand(fixture.targetItem, null, null), 2, UUID.randomUUID(),
                new RequestHash("2".repeat(64)))).isInstanceOfSatisfying(ApplicationException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.VERSION_CONFLICT));
        assertThatThrownBy(() -> fixture.service.reassign(fixture.actor, fixture.session.id(), new TimeTrackingModels.TimeSessionReassignCommand(fixture.targetItem, null, null), 0, UUID.randomUUID(),
                new RequestHash("3".repeat(64)))).isInstanceOfSatisfying(ApplicationException.class,
                failure -> assertThat(failure.reason()).isEqualTo("SESSION_NOT_EDITABLE"));
        assertThatThrownBy(() -> fixture.service.reassign(fixture.actor, stopped.id(), new TimeTrackingModels.TimeSessionReassignCommand(stopped.workItemId(), null, null), 3, UUID.randomUUID(),
                new RequestHash("4".repeat(64)))).isInstanceOfSatisfying(ApplicationException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.VALIDATION_FAILED));
        verify(fixture.guard, never()).lockForFactWrite(any(), any());
        fixture.verifyNoWrites();
    }

    @Test
    void switchingToACategoryStopsWorkItemTimersWithoutAProjectGuardAndPublishesOnlyTheWorkItemEvent() {
        var fixture = new TimerFixture(false);
        fixture.service.command(fixture.actor, "switch", new TimeTrackingService.Input(null, null, null, null, null, null,
                TimeCategory.MEETING, null, null, null), 1, UUID.randomUUID(), new RequestHash("d".repeat(64)));

        var saved = ArgumentCaptor.forClass(TimeTrackingModels.Session.class);
        verify(fixture.timers, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().getFirst().id()).isEqualTo(fixture.session.id());
        var created = saved.getAllValues().getLast();
        assertThat(created.timeCategory()).isEqualTo(TimeCategory.MEETING);
        assertThat(created.projectId()).isNull();
        assertThat(created.workItemId()).isNull();
        assertThat(created.stoppedAt()).isNull();
        verify(fixture.events, times(1)).append(any());
        verify(fixture.audits, times(2)).append(any());
        verifyNoInteractions(fixture.guard);
        verify(fixture.timers).advanceProjects(fixture.company, List.of(fixture.oldProject));
    }

    @Test
    void categoryFillTakesNoProjectLockAndOnlyReachesTheAudit() {
        var fixture = new TimerFixture(false);
        var command = new TimeTrackingModels.TimeSessionFillCommand(TimeTrackingModels.TimeSessionFillMode.GAPS, List.of(
                new TimeTrackingModels.TimeSessionFillEntry(null, TimerFixture.NOW.minusSeconds(7200), TimerFixture.NOW.minusSeconds(3600),
                        TimeCategory.BUSINESS_TRIP, null)));
        fixture.service.fill(fixture.actor, command, UUID.randomUUID(), new RequestHash("e".repeat(64)));

        var saved = ArgumentCaptor.forClass(TimeTrackingModels.Session.class);
        verify(fixture.timers).save(saved.capture());
        assertThat(saved.getValue().timeCategory()).isEqualTo(TimeCategory.BUSINESS_TRIP);
        assertThat(saved.getValue().source()).isEqualTo("MANUAL");
        verify(fixture.timers).stateVersion(fixture.company, fixture.user, true);
        verifyNoInteractions(fixture.guard, fixture.events, fixture.deletion);
        verify(fixture.audits).append(any());
        verify(fixture.timers, never()).advanceProjects(any(), any());
    }

    @Test
    void fillRejectsEntriesWithBothOrNeitherSubject() {
        var fixture = new TimerFixture(false);
        for (var entry : List.of(
                new TimeTrackingModels.TimeSessionFillEntry(null, TimerFixture.NOW.minusSeconds(60), TimerFixture.NOW, null, null),
                new TimeTrackingModels.TimeSessionFillEntry(fixture.targetItem, TimerFixture.NOW.minusSeconds(60), TimerFixture.NOW, TimeCategory.LEARNING, null)))
            assertThatThrownBy(() -> fixture.service.fill(fixture.actor, new TimeTrackingModels.TimeSessionFillCommand(
                    TimeTrackingModels.TimeSessionFillMode.GAPS, List.of(entry)), UUID.randomUUID(), new RequestHash("f".repeat(64))))
                    .isInstanceOfSatisfying(ApplicationException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.VALIDATION_FAILED));
        fixture.verifyNoWrites();
    }

    @Test
    void archivingAProjectStopsOnlyItsTimersAndLeavesRunningCategoryTimers() {
        var fixture = new TimerFixture(false);
        var category = new TimeTrackingModels.Session(UUID.randomUUID(), fixture.company, null, null, fixture.user,
                TimerFixture.NOW.minusSeconds(600), null, "TIMER", 0, null, null, TimeCategory.MEETING, null);
        when(fixture.timers.runningUserIds(fixture.company, fixture.oldProject)).thenReturn(List.of(fixture.user));
        when(fixture.timers.running(fixture.company, fixture.user)).thenReturn(List.of(category, fixture.session));

        assertThat(fixture.service.stopRunningInArchivedProject(fixture.actor, fixture.oldProject)).isEqualTo(1);
        var saved = ArgumentCaptor.forClass(TimeTrackingModels.Session.class);
        verify(fixture.timers).save(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo(fixture.session.id());
    }

    @Test
    void ownCategoryRecordsAreEditedUnderTheTimerLockWithoutDomainEvents() {
        var fixture = new TimerFixture(false);
        var record = new TimeTrackingModels.Session(UUID.randomUUID(), fixture.company, null, null, fixture.user,
                TimerFixture.NOW.minusSeconds(7200), TimerFixture.NOW.minusSeconds(3600), "MANUAL", 2, null, null, TimeCategory.LEARNING, null);
        when(fixture.timers.find(fixture.company, record.id())).thenReturn(Optional.of(record));
        var input = new TimeTrackingService.Input(null, record.id(), record.startedAt(), record.stoppedAt().plusSeconds(1800), null, null, null, null, null, null);

        var result = fixture.service.ownSession(fixture.actor, "edit", record.id(), input, 2, UUID.randomUUID(), new RequestHash("9".repeat(64)));
        assertThat(result.etag()).isEqualTo("\"3\"");
        var saved = ArgumentCaptor.forClass(TimeTrackingModels.Session.class);
        verify(fixture.timers).save(saved.capture());
        assertThat(saved.getValue().timeCategory()).isEqualTo(TimeCategory.LEARNING);
        assertThat(saved.getValue().stoppedAt()).isEqualTo(TimerFixture.NOW.minusSeconds(1800));
        verify(fixture.timers).sameSubjectOverlaps(fixture.company, fixture.user, new TimeSubject.Category(TimeCategory.LEARNING, null),
                record.startedAt(), TimerFixture.NOW.minusSeconds(1800), record.id());
        verifyNoInteractions(fixture.events, fixture.guard);
        assertThatThrownBy(() -> fixture.service.ownSession(fixture.actor, "delete", record.id(), input, 1, UUID.randomUUID(),
                new RequestHash("8".repeat(64)))).isInstanceOfSatisfying(ApplicationException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.VERSION_CONFLICT));
    }

    @Test
    void workItemOnlyBodiesKeepTheirRequestHashShape() {
        var json = new ObjectMapper();
        var input = json.valueToTree(new TimeTrackingService.Input(UUID.randomUUID(), null, null, null, null, null, null, null, null, null));
        assertThat(input.has("timeCategory")).isFalse();
        assertThat(input.has("timeCategories")).isFalse();
        var entry = json.valueToTree(new TimeTrackingModels.TimeSessionFillEntry(UUID.randomUUID(), TimerFixture.NOW, TimerFixture.NOW, null, null));
        assertThat(entry.has("timeCategory")).isFalse();
    }

    @Test
    void categoryStartRejectsAnUnavailableProject() {
        var fixture = new TimerFixture(false);
        var input = new TimeTrackingService.Input(null, null, null, null, null, null,
                TimeCategory.MEETING, null, fixture.targetProject, null);
        assertThatThrownBy(() -> fixture.service.command(fixture.actor, "start", input, 1,
                UUID.randomUUID(), new RequestHash("a".repeat(64))))
                .isInstanceOfSatisfying(ApplicationException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.VALIDATION_FAILED);
                    assertThat(failure.fieldViolations()).extracting(violation -> violation.code()).containsExactly("PROJECT_UNAVAILABLE");
                });
        fixture.verifyNoWrites();
    }

    @Test
    void startingTheSameCategoryInAnotherProjectStopsItsPreviousTimer() {
        var fixture = new TimerFixture(false);
        var meeting = new TimeTrackingModels.Session(UUID.randomUUID(), fixture.company, null, null, fixture.user,
                TimerFixture.NOW.minusSeconds(600), null, "TIMER", 0, null, null, TimeCategory.MEETING, fixture.oldProject);
        when(fixture.timers.running(fixture.company, fixture.user)).thenReturn(List.of(meeting), List.of(meeting), List.of());
        when(fixture.access.findWritableProjects(fixture.actor)).thenReturn(List.of(
                new ProjectAccessSnapshotQuery.WritableProject(fixture.targetProject, "项目 B", "B")));
        var input = new TimeTrackingService.Input(null, null, null, null, null, null,
                TimeCategory.MEETING, null, fixture.targetProject, null);
        fixture.service.command(fixture.actor, "start", input, 1, UUID.randomUUID(), new RequestHash("b".repeat(64)));

        var saved = ArgumentCaptor.forClass(TimeTrackingModels.Session.class);
        verify(fixture.timers, times(2)).save(saved.capture());
        var stopped = saved.getAllValues().getFirst();
        assertThat(stopped.id()).isEqualTo(meeting.id());
        assertThat(stopped.categoryProjectId()).isEqualTo(fixture.oldProject);
        assertThat(stopped.stoppedAt()).isEqualTo(TimerFixture.NOW);
        var started = saved.getAllValues().getLast();
        assertThat(started.timeCategory()).isEqualTo(TimeCategory.MEETING);
        assertThat(started.categoryProjectId()).isEqualTo(fixture.targetProject);
        assertThat(started.stoppedAt()).isNull();
        verify(fixture.timers).advanceState(fixture.company, fixture.user);
        verifyNoInteractions(fixture.guard, fixture.deletion, fixture.events);
    }

    @Test
    void migratedCategoryCannotBeStartedFilledOrReassignedTo() {
        var fixture = new TimerFixture(false);
        var start = new TimeTrackingService.Input(null, null, null, null, null, null, TimeCategory.MIGRATED, null, null, null);
        assertReserved(() -> fixture.service.command(fixture.actor, "start", start, 1, UUID.randomUUID(), new RequestHash("a".repeat(64))));
        var fill = new TimeTrackingModels.TimeSessionFillCommand(TimeTrackingModels.TimeSessionFillMode.PARALLEL, List.of(
                new TimeTrackingModels.TimeSessionFillEntry(null, TimerFixture.NOW.minusSeconds(7200), TimerFixture.NOW.minusSeconds(3600), TimeCategory.MIGRATED, null)));
        assertReserved(() -> fixture.service.fill(fixture.actor, fill, UUID.randomUUID(), new RequestHash("b".repeat(64))));
        var stopped = fixture.stoppedSession(0);
        var reassign = new TimeTrackingModels.TimeSessionReassignCommand(null, TimeCategory.MIGRATED, null);
        assertReserved(() -> fixture.service.reassign(fixture.actor, stopped.id(), reassign, 0, UUID.randomUUID(), new RequestHash("c".repeat(64))));
        fixture.verifyNoWrites();
    }

    @Test
    void migratedRecordsCannotBeEditedDeletedOrReassigned() {
        var fixture = new TimerFixture(false);
        var record = migratedSession(fixture);
        var input = new TimeTrackingService.Input(null, record.id(), record.startedAt(), record.stoppedAt().plusSeconds(60), null, null, null, null, null, null);
        for (String action : List.of("edit", "delete"))
            assertReadOnly(() -> fixture.service.ownSession(fixture.actor, action, record.id(), input, 0, UUID.randomUUID(), new RequestHash("d".repeat(64))));
        var reassign = new TimeTrackingModels.TimeSessionReassignCommand(null, TimeCategory.MEETING, null);
        assertReadOnly(() -> fixture.service.reassign(fixture.actor, record.id(), reassign, 0, UUID.randomUUID(), new RequestHash("e".repeat(64))));
        fixture.verifyNoWrites();
    }

    @Test
    void migratedWorkItemRecordsCannotBeEditedOrDeleted() {
        var fixture = new TimerFixture(false);
        var record = new TimeTrackingModels.Session(UUID.randomUUID(), fixture.company, fixture.targetProject, fixture.targetItem, fixture.user,
                TimerFixture.NOW.minusSeconds(7200), TimerFixture.NOW.minusSeconds(3600), "MANUAL", 0, null, null, null, null, true);
        when(fixture.timers.find(fixture.company, record.id())).thenReturn(Optional.of(record));
        var input = new TimeTrackingService.Input(null, record.id(), record.startedAt(), record.stoppedAt().plusSeconds(60), null, null, null, null, null, null);
        for (String action : List.of("edit", "delete"))
            assertReadOnly(() -> fixture.service.ownSession(fixture.actor, action, record.id(), input, 0, UUID.randomUUID(), new RequestHash("d".repeat(64))));
        fixture.verifyNoWrites();
    }

    @Test
    void migratedRecordsAreListedAsReadOnly() {
        var fixture = new TimerFixture(false);
        var migrated = migratedSession(fixture);
        var learning = new TimeTrackingModels.Session(UUID.randomUUID(), fixture.company, null, null, fixture.user,
                TimerFixture.NOW.minusSeconds(3000), TimerFixture.NOW.minusSeconds(2000), "MANUAL", 0, null, null, TimeCategory.LEARNING, null);
        when(fixture.timers.mySessions(eq(fixture.company), eq(fixture.user), any(), any())).thenReturn(List.of(
                new TimeTrackingModels.MySessionRow(migrated, null, null, null, false, false),
                new TimeTrackingModels.MySessionRow(learning, null, null, null, false, false)));
        var page = fixture.service.mySessions(fixture.actor, TimerFixture.NOW.minusSeconds(86400), TimerFixture.NOW);
        assertThat(page.items()).extracting(item -> item.session().canEdit()).containsExactly(false, true);
    }

    private static TimeTrackingModels.Session migratedSession(TimerFixture fixture) {
        var record = new TimeTrackingModels.Session(UUID.randomUUID(), fixture.company, null, null, fixture.user,
                TimerFixture.NOW.minusSeconds(7200), TimerFixture.NOW.minusSeconds(3600), "MANUAL", 0, null, null, TimeCategory.MIGRATED, null);
        when(fixture.timers.find(fixture.company, record.id())).thenReturn(Optional.of(record));
        return record;
    }

    private static void assertReserved(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApplicationException.class, failure -> {
            assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.VALIDATION_FAILED);
            assertThat(failure.fieldViolations()).extracting(violation -> violation.code()).containsExactly("TIME_CATEGORY_RESERVED");
        });
    }

    private static void assertReadOnly(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApplicationException.class, failure -> {
            assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.INVALID_STATE_TRANSITION);
            assertThat(failure.reason()).isEqualTo("MIGRATED_TIME_READ_ONLY");
        });
    }

    private static final class TimerFixture {
        static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
        final UUID company = UUID.randomUUID(), user = UUID.randomUUID(), targetItem = UUID.randomUUID();
        final UUID oldProject, targetProject;
        final CurrentActor actor = new CurrentActor(user, company, 0, Set.of());
        final TimeTrackingRepository timers = mock(TimeTrackingRepository.class);
        final WorkItemRepository items = mock(WorkItemRepository.class);
        final ProjectAccessSnapshotQuery access = mock(ProjectAccessSnapshotQuery.class);
        final ProjectFactWriteGuard guard = mock(ProjectFactWriteGuard.class);
        final ProjectDeletionQuery deletion = mock(ProjectDeletionQuery.class);
        final TransactionalEventPort events = mock(TransactionalEventPort.class);
        final SecurityAuditAppendPort audits = mock(SecurityAuditAppendPort.class);
        final TimeTrackingModels.Session session;
        final TimeTrackingService service;

        TimerFixture(boolean reverseProjects) {
            UUID first = UUID.fromString("00000000-0000-4000-8000-000000000001");
            UUID second = UUID.fromString("00000000-0000-4000-8000-000000000002");
            oldProject = reverseProjects ? second : first;
            targetProject = reverseProjects ? first : second;
            session = new TimeTrackingModels.Session(UUID.randomUUID(), company, oldProject, UUID.randomUUID(), user,
                    NOW.minusSeconds(3600), null, "TIMER", 0, null, null);
            when(timers.running(company, user)).thenReturn(List.of(session), List.of(session), List.of());
            when(timers.find(company, session.id())).thenReturn(Optional.of(session));
            when(timers.stateVersion(company, user, true)).thenReturn(1L);
            when(timers.recentItems(company, user)).thenReturn(List.of());
            when(items.findLocator(company, targetItem)).thenReturn(Optional.of(new WorkItemModels.WorkItemLocator(
                    targetItem, targetProject, UUID.randomUUID())));
            when(items.lockProjectItem(company, targetProject, targetItem)).thenReturn(Optional.of(mock(WorkItem.class)));
            when(access.findVisible(actor, targetProject)).thenReturn(Optional.of(mock(ProjectAccessSnapshot.class)));
            when(deletion.lockForProjection(company, oldProject)).thenReturn(Optional.of(project(oldProject, "ACTIVE", null)));
            when(deletion.lockForProjection(company, targetProject)).thenReturn(Optional.of(project(targetProject, "ACTIVE", null)));
            when(guard.lockForFactWrite(actor, targetProject)).thenReturn(new ProjectFactWriteSnapshot(targetProject, company,
                    "TEST", ProjectFactWriteSnapshot.ProjectLifecycle.ACTIVE, ProjectFactWriteSnapshot.ActorProjectAccess.MEMBER));
            var executor = mock(IdempotentCommandExecutor.class);
            when(executor.execute(any(), any())).thenAnswer(call -> IdempotencyExecutionResult.executed(
                    call.<Supplier<StoredCommandResult>>getArgument(1).get()));
            service = new TimeTrackingService(timers, mock(TimeShareMaintainer.class), items, access, mock(ProjectConnectionTargetQuery.class), guard, deletion, mock(MinimalUserSnapshotQuery.class),
                    executor, events, audits, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        }

        ProjectDeletionQuery.State project(UUID id, String lifecycle, Instant purgeStartedAt) {
            return new ProjectDeletionQuery.State(id, company, "TEST", lifecycle, user, 0, null, null, null, purgeStartedAt);
        }

        TimeTrackingModels.Session stoppedSession(long version) {
            UUID item = UUID.randomUUID();
            var stopped = new TimeTrackingModels.Session(UUID.randomUUID(), company, oldProject, item, user,
                    NOW.minusSeconds(7200), NOW.minusSeconds(3600), "TIMER", version, null, null);
            when(timers.find(company, stopped.id())).thenReturn(Optional.of(stopped));
            when(timers.mySessions(eq(company), eq(user), any(), any()))
                    .thenReturn(List.of(new TimeTrackingModels.MySessionRow(stopped, "1", "原工作项", null, false, false)));
            when(guard.lockForFactWrite(actor, oldProject)).thenReturn(new ProjectFactWriteSnapshot(oldProject, company,
                    "TEST", ProjectFactWriteSnapshot.ProjectLifecycle.ACTIVE, ProjectFactWriteSnapshot.ActorProjectAccess.MEMBER));
            when(items.lockProjectItem(company, oldProject, item)).thenReturn(Optional.of(mock(WorkItem.class)));
            when(items.findLocator(company, item)).thenReturn(Optional.of(new WorkItemModels.WorkItemLocator(item, oldProject, UUID.randomUUID())));
            return stopped;
        }

        void command(String action) {
            service.command(actor, action, new TimeTrackingService.Input(action.equals("stop") ? null : targetItem,
                    session.id(), null, null, null, null, null, null, null, null), 1, UUID.randomUUID(), new RequestHash("a".repeat(64)));
        }

        void verifyNoWrites() {
            verify(timers, never()).save(any());
            verify(timers, never()).advanceProjects(any(), any());
            verify(timers, never()).advanceState(any(), any());
            verifyNoInteractions(events, audits);
        }
    }
}
