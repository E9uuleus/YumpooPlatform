package com.yumpoo.platform.reporting.application;

import com.yumpoo.platform.catalog.api.CompanyProjectQuery;
import com.yumpoo.platform.catalog.api.MemberProjectQuery;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationSnapshot;
import com.yumpoo.platform.workitem.api.TeamWorkQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkHoursAnomalyScheduledJobTest {
    @Test
    void catchesUpEveryMissedCompanyDayAcrossDstWithoutDuplicateEvents() {
        var repository = mock(WorkHoursAlertRepository.class);
        var projects = mock(CompanyProjectQuery.class);
        var work = mock(TeamWorkQuery.class);
        var company = mock(CompanyConfigurationQuery.class);
        var events = mock(TransactionalEventPort.class);
        UUID companyId = UUID.randomUUID(), projectId = UUID.randomUUID(), userId = UUID.randomUUID();
        ZoneId zone = ZoneId.of("America/New_York");
        Instant planned = OffsetDateTime.parse("2026-03-07T23:30:00-05:00").toInstant();
        Instant now = OffsetDateTime.parse("2026-03-10T00:15:00-04:00").toInstant();
        when(company.current()).thenReturn(new CompanyConfigurationSnapshot(companyId, "Yumpoo", zone, DayOfWeek.MONDAY, 480, 0));
        when(projects.listForScheduler(companyId)).thenReturn(List.of(new MemberProjectQuery.Project(projectId, "交付", "P001", "ACTIVE")));
        var recorded = new HashSet<LocalDate>();
        when(work.timeEntriesForScheduler(eq(companyId), eq(List.of(projectId)), any(), any(), eq(zone), eq(now)))
                .thenAnswer(call -> List.of(new TeamWorkQuery.TimeEntry(userId, UUID.randomUUID(), projectId, "P001-1", "任务", call.getArgument(2), 13 * 3_600_000L)));
        when(repository.recordAlert(eq(companyId), eq(userId), any(), anyLong(), eq(720), eq(now)))
                .thenAnswer(call -> recorded.add(call.getArgument(2)) ? Optional.of(UUID.randomUUID()) : Optional.empty());
        var job = new WorkHoursAnomalyScheduledJob(repository, projects, work, company, events, new ObjectMapper(), Clock.fixed(now, zone));
        String key = "scheduled:WORK_HOURS_ANOMALY:" + planned.getEpochSecond();
        job.run(companyId, key, "catch-up");
        job.run(companyId, key, "retry");
        for (LocalDate day = LocalDate.parse("2026-03-06"); day.isBefore(LocalDate.parse("2026-03-10")); day = day.plusDays(1)) {
            verify(work, times(2)).timeEntriesForScheduler(companyId, List.of(projectId), day, day, zone, now);
        }
        ArgumentCaptor<EventDraft> event = ArgumentCaptor.forClass(EventDraft.class);
        verify(events, times(4)).append(event.capture());
        assertThat(event.getAllValues()).extracting(e -> e.payload().path("workDate").asText())
                .containsExactly("2026-03-06", "2026-03-07", "2026-03-08", "2026-03-09");
        verify(work, never()).timeEntriesForScheduler(eq(companyId), any(), eq(LocalDate.parse("2026-03-10")), any(), any(), any());
    }

    @Test
    void checksTheDayBeforeThePlannedTimeAndPublishesOnlyANewOverThresholdAlert() {
        var repository = mock(WorkHoursAlertRepository.class);
        var projects = mock(CompanyProjectQuery.class);
        var work = mock(TeamWorkQuery.class);
        var company = mock(CompanyConfigurationQuery.class);
        var events = mock(TransactionalEventPort.class);
        UUID companyId = UUID.randomUUID(), projectId = UUID.randomUUID(), user1 = UUID.randomUUID(), user2 = UUID.randomUUID(), alertId = UUID.randomUUID();
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant planned = OffsetDateTime.parse("2026-10-12T00:30:00+08:00").toInstant();
        Instant now = planned.plusSeconds(60);
        LocalDate day = LocalDate.parse("2026-10-11");
        when(company.current()).thenReturn(new CompanyConfigurationSnapshot(companyId, "Yumpoo", zone, DayOfWeek.MONDAY, 480, 0));
        when(repository.setting(companyId)).thenReturn(Optional.of(new WorkHoursAlertRepository.Setting(600, 1)));
        when(projects.listForScheduler(companyId)).thenReturn(List.of(new MemberProjectQuery.Project(projectId, "交付", "P001", "ACTIVE")));
        when(work.timeEntriesForScheduler(companyId, List.of(projectId), day, day, zone, now)).thenReturn(List.of(
                new TeamWorkQuery.TimeEntry(user1, UUID.randomUUID(), projectId, "P001-1", "任务 1", day, 11 * 3_600_000L),
                new TeamWorkQuery.TimeEntry(user2, UUID.randomUUID(), projectId, "P001-2", "任务 2", day, 9 * 3_600_000L)));
        when(repository.recordAlert(companyId, user1, day, 11 * 3_600_000L, 600, now)).thenReturn(Optional.of(alertId), Optional.empty());
        var job = new WorkHoursAnomalyScheduledJob(repository, projects, work, company, events, new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC));
        String triggerKey = "scheduled:WORK_HOURS_ANOMALY:" + planned.getEpochSecond();

        job.run(companyId, triggerKey, "test-request");
        job.run(companyId, triggerKey, "test-request-again");

        verify(work, times(2)).timeEntriesForScheduler(companyId, List.of(projectId), day, day, zone, now);
        verify(repository, never()).recordAlert(eq(companyId), eq(user2), any(), anyLong(), anyInt(), any());
        ArgumentCaptor<EventDraft> event = ArgumentCaptor.forClass(EventDraft.class);
        verify(events).append(event.capture());
        assertThat(event.getValue().aggregateId()).isEqualTo(alertId);
        assertThat(event.getValue().payload().path("userId").asText()).isEqualTo(user1.toString());
        assertThat(event.getValue().payload().path("workDate").asText()).isEqualTo("2026-10-11");
    }
}
