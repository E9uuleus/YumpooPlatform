package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectConnectionTargetQuery;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.*;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshot;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.workitem.application.MigratedTimeImportService.Entry;
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
    private final CurrentActor actor = new CurrentActor(admin, company, 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
    private final TimeTrackingRepository timers = mock(TimeTrackingRepository.class);
    private final TimeShareMaintainer shares = mock(TimeShareMaintainer.class);
    private final ProjectConnectionTargetQuery projects = mock(ProjectConnectionTargetQuery.class);
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
        when(timers.preferences(company, member)).thenReturn(Optional.empty());
        when(timers.mySessions(eq(company), eq(member), any(), any())).thenReturn(List.of());
        when(shares.rebuild(eq(company), eq(member), any(), any())).thenReturn(Set.of());
        service = new MigratedTimeImportService(timers, shares, projects, users, executor, audits, json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private StoredCommandResult run(CurrentActor who, Request request) {
        return service.execute(who, ZONE, request, UUID.randomUUID(), new RequestHash("a".repeat(64)));
    }

    @Test
    void onlyAdministratorsMayImport() {
        var plainMember = new CurrentActor(member, company, 0, Set.of());
        assertThatThrownBy(() -> run(plainMember, new Request(true, null, List.of(new Entry(member, DAY, 60)))))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.errorCode()).isEqualTo(StandardErrorCode.ACCESS_DENIED));
    }

    @Test
    void dryRunLaysOutDaysWithoutWriting() {
        var result = json.readTree(run(actor, new Request(true, null, List.of(new Entry(member, DAY, 120), new Entry(gone, DAY, 60)))).responseJson());
        assertThat(result.path("importedDayCount").asInt()).isEqualTo(1);
        assertThat(result.path("failedDayCount").asInt()).isEqualTo(1);
        assertThat(result.path("importedMinutes").asLong()).isEqualTo(120);
        assertThat(result.path("days").get(0).path("startedAt").asText()).isEqualTo("2026-10-20T01:00:00Z");
        assertThat(result.path("days").get(0).path("stoppedAt").asText()).isEqualTo("2026-10-20T03:00:00Z");
        assertThat(result.path("days").get(1).path("outcome").asText()).isEqualTo("ERROR");
        assertThat(result.path("days").get(1).path("issues").get(0).path("code").asText()).isEqualTo("USER_INVALID");
        verify(timers, never()).save(any());
        verify(timers, never()).stateVersion(any(), any(), eq(true));
        verifyNoInteractions(audits, shares);
    }

    @Test
    void commitReplacesOldSessionsRebuildsSharesOncePerMemberAndAuditsOnce() {
        var old = new TimeTrackingModels.Session(UUID.randomUUID(), company, null, null, member, DAY.atTime(9, 0).atZone(ZONE).toInstant(),
                DAY.atTime(10, 0).atZone(ZONE).toInstant(), "MANUAL", 0, null, null, TimeCategory.MIGRATED, null);
        when(timers.mySessions(eq(company), eq(member), any(), any())).thenReturn(List.of(new TimeTrackingModels.MySessionRow(old, null, null, null, false, false)));
        var entries = List.of(new Entry(member, DAY, 90), new Entry(member, DAY.plusDays(1), 0));
        var result = json.readTree(run(actor, new Request(false, null, entries)).responseJson());

        assertThat(result.path("replacedMinutes").asLong()).isEqualTo(60);
        assertThat(result.path("days").get(0).path("issues").get(0).path("code").asText()).isEqualTo("REPLACES_PREVIOUS");
        verify(timers).stateVersion(company, member, true);
        var saved = ArgumentCaptor.forClass(TimeTrackingModels.Session.class);
        verify(timers, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().getFirst().id()).isEqualTo(old.id());
        assertThat(saved.getAllValues().getFirst().deletedAt()).isEqualTo(NOW);
        var created = saved.getAllValues().getLast();
        assertThat(created.timeCategory()).isEqualTo(TimeCategory.MIGRATED);
        assertThat(created.source()).isEqualTo("MANUAL");
        assertThat(created.projectId()).isNull();
        verify(shares, times(1)).rebuild(eq(company), eq(member), any(), any());
        verify(audits, times(1)).append(any(SecurityAuditDraft.class));
    }

    @Test
    void rejectsDuplicateDaysAndUnavailableProjects() {
        assertThatThrownBy(() -> run(actor, new Request(true, null, List.of(new Entry(member, DAY, 60), new Entry(member, DAY, 30)))))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.fieldViolations()).extracting(v -> v.code()).containsExactly("DUPLICATE_ENTRY"));
        UUID archived = UUID.randomUUID();
        when(projects.findByIds(company, Set.of(archived))).thenReturn(Map.of(archived,
                new ProjectConnectionTargetQuery.ConnectTargetProjectSnapshot(archived, "P", "项目", ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED)));
        assertThatThrownBy(() -> run(actor, new Request(true, archived, List.of(new Entry(member, DAY, 60)))))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.fieldViolations()).extracting(v -> v.code()).containsExactly("PROJECT_UNAVAILABLE"));
    }
}
