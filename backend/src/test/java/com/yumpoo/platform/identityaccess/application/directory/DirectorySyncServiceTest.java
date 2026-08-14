package com.yumpoo.platform.identityaccess.application.directory;

import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DirectorySyncServiceTest {

    private static final UUID COMPANY_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000001"
    );
    private static final UUID RUN_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000104"
    );
    private static final UUID LEASE_TOKEN = UUID.fromString(
            "00000000-0000-4000-8000-000000000105"
    );
    private static final Duration LEASE = Duration.ofMinutes(5);

    private CompanyConfigurationQuery companyQuery;
    private DirectorySyncRepository repository;
    private FullDirectoryScanCollector collector;
    private WeComDirectoryProfileGateway profileGateway;
    private DirectorySyncItemApplyService itemApplyService;
    private DirectorySyncService service;

    @BeforeEach
    void setUp() {
        companyQuery = mock(CompanyConfigurationQuery.class);
        repository = mock(DirectorySyncRepository.class);
        collector = mock(FullDirectoryScanCollector.class);
        profileGateway = mock(WeComDirectoryProfileGateway.class);
        itemApplyService = mock(DirectorySyncItemApplyService.class);
        when(companyQuery.current()).thenReturn(new CompanyConfigurationSnapshot(
                COMPANY_ID,
                "Yumpoo",
                ZoneId.of("Asia/Shanghai"),
                DayOfWeek.MONDAY,
                480,
                0
        ));
        service = new DirectorySyncService(
                companyQuery,
                repository,
                collector,
                profileGateway,
                itemApplyService,
                new DirectorySyncSettings(1000, LEASE)
        );
    }

    @Test
    void returnsConcurrentSnapshotWithoutCallingProvider() {
        DirectorySyncRunSnapshot running = snapshot(DirectorySyncRunStatus.RUNNING, null);
        when(repository.claim(COMPANY_ID, command(), LEASE))
                .thenReturn(new DirectorySyncClaim(running, null, false));

        DirectorySyncRunSnapshot result = service.execute(command());

        assertThat(result).isEqualTo(running);
        verifyNoInteractions(collector, profileGateway, itemApplyService);
    }

    @Test
    void failsBeforeAnyUserWriteWhenScanFails() {
        DirectorySyncRunSnapshot running = snapshot(DirectorySyncRunStatus.RUNNING, null);
        DirectorySyncRunSnapshot failed = snapshot(
                DirectorySyncRunStatus.FAILED,
                "DIRECTORY_SCAN_PROVIDER_FAILED"
        );
        when(repository.claim(COMPANY_ID, command(), LEASE))
                .thenReturn(new DirectorySyncClaim(running, LEASE_TOKEN, true));
        when(collector.collect(any())).thenThrow(new DirectorySyncException(
                "DIRECTORY_SCAN_PROVIDER_FAILED",
                "The provider scan failed closed"
        ));
        when(repository.fail(
                RUN_ID,
                LEASE_TOKEN,
                "DIRECTORY_SCAN_PROVIDER_FAILED",
                "The provider scan failed closed",
                command().actor()
        )).thenReturn(failed);

        DirectorySyncRunSnapshot result = service.execute(command());

        assertThat(result).isEqualTo(failed);
        verifyNoInteractions(profileGateway, itemApplyService);
        verify(repository, never()).beginApplying(any(), any(), any());
    }

    @Test
    void failsBeforeAnyUserWriteWhenProfileReadFails() {
        DirectorySyncRunSnapshot running = snapshot(DirectorySyncRunStatus.RUNNING, null);
        DirectorySyncRunSnapshot failed = snapshot(
                DirectorySyncRunStatus.FAILED,
                "DIRECTORY_PROFILE_PERMISSION_DENIED"
        );
        DirectoryScanResult scan = new DirectoryScanResult(
                List.of("member-a"),
                DirectoryScanResult.CursorTerminationMode.EXPLICIT_EMPTY,
                1,
                "a".repeat(64),
                "b".repeat(64)
        );
        when(repository.claim(COMPANY_ID, command(), LEASE))
                .thenReturn(new DirectorySyncClaim(running, LEASE_TOKEN, true));
        when(collector.collect(any())).thenReturn(scan);
        when(profileGateway.fetchDepartmentNames()).thenReturn(Map.of(3L, "研发部"));
        when(profileGateway.fetchMemberProfile("member-a"))
                .thenThrow(new DirectorySyncException(
                        "DIRECTORY_PROFILE_PERMISSION_DENIED",
                        "The member profile provider rejected the request"
                ));
        when(repository.fail(
                RUN_ID,
                LEASE_TOKEN,
                "DIRECTORY_PROFILE_PERMISSION_DENIED",
                "The member profile provider rejected the request",
                command().actor()
        )).thenReturn(failed);

        DirectorySyncRunSnapshot result = service.execute(command());

        assertThat(result).isEqualTo(failed);
        verifyNoInteractions(itemApplyService);
        verify(repository, never()).beginApplying(any(), any(), any());
    }

    @Test
    void stopsAtFirstItemFailureAndDoesNotApplyRemainingMembers() {
        DirectorySyncRunSnapshot running = snapshot(DirectorySyncRunStatus.RUNNING, null);
        DirectorySyncRunSnapshot failed = snapshot(
                DirectorySyncRunStatus.FAILED,
                "DIRECTORY_APPLY_FAILED"
        );
        List<String> memberIds = List.of("member-a", "member-b", "member-c");
        DirectoryScanResult scan = new DirectoryScanResult(
                memberIds,
                DirectoryScanResult.CursorTerminationMode.EXPLICIT_EMPTY,
                1,
                "a".repeat(64),
                "b".repeat(64)
        );
        Map<Long, String> departments = Map.of(3L, "研发部");
        List<WeComMemberProfile> profiles = memberIds.stream()
                .map(id -> DirectoryProfileMapper.map(raw(id), departments))
                .toList();
        when(repository.claim(COMPANY_ID, command(), LEASE))
                .thenReturn(new DirectorySyncClaim(running, LEASE_TOKEN, true));
        when(collector.collect(any())).thenReturn(scan);
        when(profileGateway.fetchDepartmentNames()).thenReturn(departments);
        memberIds.forEach(id -> when(profileGateway.fetchMemberProfile(id)).thenReturn(raw(id)));
        when(repository.stagedProfiles(RUN_ID, LEASE_TOKEN)).thenReturn(profiles);
        doNothing().doThrow(new IllegalStateException("synthetic write failure"))
                .when(itemApplyService)
                .apply(eq(RUN_ID), eq(LEASE_TOKEN), any(), eq(LEASE));
        when(repository.failDuringApply(
                RUN_ID,
                LEASE_TOKEN,
                "member-b",
                command().actor()
        )).thenReturn(failed);

        DirectorySyncRunSnapshot result = service.execute(command());

        assertThat(result).isEqualTo(failed);
        verify(itemApplyService).apply(RUN_ID, LEASE_TOKEN, profiles.get(0), LEASE);
        verify(itemApplyService).apply(RUN_ID, LEASE_TOKEN, profiles.get(1), LEASE);
        verify(itemApplyService, never()).apply(RUN_ID, LEASE_TOKEN, profiles.get(2), LEASE);
        verify(repository, never()).complete(any(), any(), any());
    }

    private static WeComRawMemberProfile raw(String externalUserId) {
        return new WeComRawMemberProfile(
                externalUserId,
                "Member",
                DirectoryOptionalField.unavailable(),
                DirectoryOptionalField.clear(),
                List.of(3L)
        );
    }

    private static DirectorySyncCommand command() {
        return new DirectorySyncCommand(
                "m104-service-test",
                DirectorySyncTriggerType.SCHEDULED,
                EventActor.system("DIRECTORY_SYNC_TEST"),
                "m104-service-test"
        );
    }

    private static DirectorySyncRunSnapshot snapshot(
            DirectorySyncRunStatus status,
            String errorCode
    ) {
        boolean completed = status != DirectorySyncRunStatus.RUNNING;
        return new DirectorySyncRunSnapshot(
                RUN_ID,
                COMPANY_ID,
                DirectorySyncTriggerType.SCHEDULED,
                completed ? DirectorySyncRunPhase.COMPLETED : DirectorySyncRunPhase.COLLECTING_IDS,
                status,
                null,
                0,
                false,
                new DirectorySyncCounts(0, 0, 0, 0, 0, 0, 0, 0, 0),
                errorCode,
                "m104-service-test",
                completed ? 1 : 0,
                Instant.parse("2026-08-14T02:00:00Z"),
                completed ? Instant.parse("2026-08-14T02:01:00Z") : null
        );
    }
}
