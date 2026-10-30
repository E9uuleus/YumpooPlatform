package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.catalog.api.CompanyProjectQuery;
import com.yumpoo.platform.catalog.api.MemberProjectQuery;
import com.yumpoo.platform.catalog.api.ProjectActiveMembershipQuery;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.*;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshot;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.workitem.application.MigratedTimeImportService.Row;
import com.yumpoo.platform.workitem.application.MigratedTimeImportService.Request;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MigratedTimeImportServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-28T02:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private final UUID company = UUID.randomUUID(), admin = UUID.randomUUID(), member = UUID.randomUUID(), gone = UUID.randomUUID();
    private final UUID project = UUID.randomUUID(), itemA = UUID.randomUUID(), itemB = UUID.randomUUID();
    private final CurrentActor actor = new CurrentActor(admin, company, 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
    private final TimeTrackingRepository timers = mock(TimeTrackingRepository.class);
    private final TimeShareMaintainer shares = mock(TimeShareMaintainer.class);
    private final CompanyProjectQuery projects = mock(CompanyProjectQuery.class);
    private final WorkItemRepository items = mock(WorkItemRepository.class);
    private final ProjectActiveMembershipQuery memberships = mock(ProjectActiveMembershipQuery.class);
    private final MinimalUserSnapshotQuery users = mock(MinimalUserSnapshotQuery.class);
    private final SecurityAuditAppendPort audits = mock(SecurityAuditAppendPort.class);
    private final ObjectMapper json = new ObjectMapper();
    private final MigratedTimeImportService service;

    MigratedTimeImportServiceTest() {
        var executor = mock(IdempotentCommandExecutor.class);
        when(executor.execute(any(), any())).thenAnswer(call -> IdempotencyExecutionResult.executed(call.<Supplier<StoredCommandResult>>getArgument(1).get()));
        when(users.findByUserIds(eq(company), any())).thenReturn(Map.of(
                member, new MinimalUserSnapshot(member, company, "成员", "ACTIVE", "ENABLED"),
                gone, new MinimalUserSnapshot(gone, company, "离职", "LEFT", "ENABLED")));
        when(projects.listForAdministrator(actor)).thenReturn(List.of(
                new MemberProjectQuery.Project(project, "交付项目", "P001", "ACTIVE"),
                new MemberProjectQuery.Project(UUID.randomUUID(), "旧项目", "P002", "ARCHIVED")));
        when(items.findTitles(company, project)).thenReturn(List.of(
                new WorkItemRepository.WorkItemTitle(itemA, "接口开发"),
                new WorkItemRepository.WorkItemTitle(itemB, "联调测试")));
        when(memberships.findActiveMemberIds(eq(company), eq(project), any())).thenReturn(Set.of(member, gone));
        when(timers.preferences(company, member)).thenReturn(Optional.empty());
        when(timers.mySessions(eq(company), eq(member), any(), any())).thenReturn(List.of());
        when(shares.rebuild(eq(company), eq(member), any(), any())).thenReturn(Set.of(project));
        service = new MigratedTimeImportService(timers, shares, projects, items, memberships, users, executor, audits, json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private StoredCommandResult run(CurrentActor who, Request request) {
        return service.execute(who, ZONE, request, UUID.randomUUID(), new RequestHash("a".repeat(64)));
    }

    @Test
    void onlyAdministratorsMayImport() {
        var plainMember = new CurrentActor(member, company, 0, Set.of());
        assertThatThrownBy(() -> run(plainMember, new Request(true, List.of(new Row(member, DAY, "交付项目", "接口开发", 60)))))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.errorCode()).isEqualTo(StandardErrorCode.ACCESS_DENIED));
    }

    @Test
    void dryRunMatchesWorkItemsAndListsRowsThatCannotBeImported() {
        var result = json.readTree(run(actor, new Request(true, List.of(
                new Row(member, DAY, "交付项目", "接口开发", 60), new Row(member, DAY, "交付项目", "联调测试", 60),
                new Row(member, DAY, "旧项目", "接口开发", 30), new Row(member, DAY, "交付项目", "不存在", 30),
                new Row(gone, DAY, "交付项目", "接口开发", 60), new Row(UUID.randomUUID(), DAY, "交付项目", "接口开发", 60)))).responseJson());
        assertThat(result.path("importedDayCount").asInt()).isEqualTo(1);
        assertThat(result.path("failedDayCount").asInt()).isEqualTo(1);
        assertThat(result.path("importedMinutes").asLong()).isEqualTo(120);
        assertThat(result.path("skippedMinutes").asLong()).isEqualTo(120);
        assertThat(result.path("skippedRows")).isEqualTo(json.valueToTree(List.of(
                new MigratedTimeImportService.SkippedRow(2, "PROJECT_NOT_FOUND"),
                new MigratedTimeImportService.SkippedRow(3, "WORK_ITEM_NOT_FOUND"),
                new MigratedTimeImportService.SkippedRow(5, "NOT_PROJECT_MEMBER"))));
        var days = new HashMap<String, tools.jackson.databind.JsonNode>();
        result.path("days").forEach(day -> days.put(day.path("userId").asText(), day));
        assertThat(days.get(member.toString()).path("startedAt").asText()).isEqualTo("2026-10-20T01:00:00Z");
        assertThat(days.get(member.toString()).path("stoppedAt").asText()).isEqualTo("2026-10-20T03:00:00Z");
        assertThat(days.get(gone.toString()).path("outcome").asText()).isEqualTo("ERROR");
        assertThat(days.get(gone.toString()).path("issues").get(0).path("code").asText()).isEqualTo("USER_INVALID");
        verify(timers, never()).save(any());
        verify(timers, never()).stateVersion(any(), any(), eq(true));
        verifyNoInteractions(audits, shares);
    }

    @Test
    void commitReplacesTheDaysMigratedTimeWithReadOnlyWorkItemRecordsInFileOrder() {
        var old = new TimeTrackingModels.Session(UUID.randomUUID(), company, null, null, member, DAY.atTime(9, 0).atZone(ZONE).toInstant(),
                DAY.atTime(10, 0).atZone(ZONE).toInstant(), "MANUAL", 0, null, null, TimeCategory.MIGRATED, null);
        when(timers.mySessions(eq(company), eq(member), any(), any())).thenReturn(List.of(new TimeTrackingModels.MySessionRow(old, null, null, null, false, false)));
        var rows = List.of(new Row(member, DAY, "交付项目", "接口开发", 60), new Row(member, DAY, "交付项目", "联调测试", 30));
        var result = json.readTree(run(actor, new Request(false, rows)).responseJson());

        assertThat(result.path("replacedMinutes").asLong()).isEqualTo(60);
        assertThat(result.path("days").get(0).path("issues").get(0).path("code").asText()).isEqualTo("REPLACES_PREVIOUS");
        verify(timers).stateVersion(company, member, true);
        var saved = ArgumentCaptor.forClass(TimeTrackingModels.Session.class);
        verify(timers, times(3)).save(saved.capture());
        assertThat(saved.getAllValues().getFirst().id()).isEqualTo(old.id());
        assertThat(saved.getAllValues().getFirst().deletedAt()).isEqualTo(NOW);
        var first = saved.getAllValues().get(1);
        var second = saved.getAllValues().get(2);
        assertThat(first.workItemId()).isEqualTo(itemA);
        assertThat(first.startedAt()).isEqualTo(DAY.atTime(9, 0).atZone(ZONE).toInstant());
        assertThat(first.stoppedAt()).isEqualTo(DAY.atTime(10, 0).atZone(ZONE).toInstant());
        assertThat(second.workItemId()).isEqualTo(itemB);
        assertThat(second.startedAt()).isEqualTo(DAY.atTime(10, 0).atZone(ZONE).toInstant());
        assertThat(second.stoppedAt()).isEqualTo(DAY.atTime(10, 30).atZone(ZONE).toInstant());
        for (var created : List.of(first, second)) {
            assertThat(created.timeCategory()).isNull();
            assertThat(created.source()).isEqualTo("MANUAL");
            assertThat(created.projectId()).isEqualTo(project);
            assertThat(created.migrated()).isTrue();
        }
        verify(shares, times(1)).rebuild(eq(company), eq(member), any(), any());
        verify(timers).advanceProjects(company, Set.of(project));
        verify(audits, times(1)).append(any(SecurityAuditDraft.class));
    }

    @Test
    void reimportKeepsEarlierRecordsOfWorkItemsItDoesNotWrite() {
        var oldA = new TimeTrackingModels.Session(UUID.randomUUID(), company, project, itemA, member,
                DAY.atTime(9, 0).atZone(ZONE).toInstant(), DAY.atTime(10, 0).atZone(ZONE).toInstant(), "MANUAL", 0, null, null, null, null, true);
        var oldB = new TimeTrackingModels.Session(UUID.randomUUID(), company, project, itemB, member,
                DAY.atTime(10, 0).atZone(ZONE).toInstant(), DAY.atTime(11, 0).atZone(ZONE).toInstant(), "MANUAL", 0, null, null, null, null, true);
        when(timers.mySessions(eq(company), eq(member), any(), any())).thenReturn(List.of(
                new TimeTrackingModels.MySessionRow(oldA, null, null, null, false, false),
                new TimeTrackingModels.MySessionRow(oldB, null, null, null, false, false)));
        var rows = List.of(new Row(member, DAY, "交付项目", "接口开发", 60), new Row(member, DAY, "交付项目", "不存在", 30));
        var result = json.readTree(run(actor, new Request(false, rows)).responseJson());

        assertThat(result.path("replacedMinutes").asLong()).isEqualTo(60);
        var saved = ArgumentCaptor.forClass(TimeTrackingModels.Session.class);
        verify(timers, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().getFirst().id()).isEqualTo(oldA.id());
        assertThat(saved.getAllValues().getFirst().deletedAt()).isEqualTo(NOW);
        var created = saved.getAllValues().get(1);
        assertThat(created.workItemId()).isEqualTo(itemA);
        assertThat(created.startedAt()).isEqualTo(DAY.atTime(9, 0).atZone(ZONE).toInstant());
        assertThat(created.stoppedAt()).isEqualTo(DAY.atTime(10, 0).atZone(ZONE).toInstant());
        assertThat(saved.getAllValues()).noneMatch(session -> session.id().equals(oldB.id()));
    }

    @Test
    void rejectsRowsOutsideTheMinuteRange() {
        assertThatThrownBy(() -> run(actor, new Request(true, List.of(new Row(member, DAY, "交付项目", "接口开发", 0)))))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.fieldViolations()).extracting(v -> v.code()).containsExactly("INVALID_RANGE"));
        assertThatThrownBy(() -> run(actor, new Request(true, List.of(
                new Row(member, DAY, "交付项目", "接口开发", 800), new Row(member, DAY, "交付项目", "联调测试", 700)))))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.fieldViolations()).extracting(v -> v.code()).containsExactly("INVALID_RANGE"));
    }
}
