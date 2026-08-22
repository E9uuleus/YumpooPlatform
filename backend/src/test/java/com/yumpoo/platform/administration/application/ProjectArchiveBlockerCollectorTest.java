package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectArchiveBlockerCollectorTest {

    @Test
    void productionCoverageIncludesRealWorkItemZeroCountProvider() {
        assertThat(new ProjectArchiveBlockerCollector(List.of(
                provider(ProjectArchiveBlockerSource.WORKITEM, 0, true))).collect(
                UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void reportsPositiveCountsInStableSourceOrder() {
        ProjectArchiveBlockerProvider worklog = provider(ProjectArchiveBlockerSource.WORKLOG, 2, true);
        ProjectArchiveBlockerProvider workitem = provider(ProjectArchiveBlockerSource.WORKITEM, 3, true);
        ProjectArchiveBlockerCollector collector = new ProjectArchiveBlockerCollector(
                List.of(worklog, workitem), Set.of(ProjectArchiveBlockerSource.WORKLOG,
                ProjectArchiveBlockerSource.WORKITEM));

        assertThat(collector.collect(UUID.randomUUID(), UUID.randomUUID()))
                .extracting("code", "count")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("OPEN_WORK_ITEMS", 3L),
                        org.assertj.core.groups.Tuple.tuple("PENDING_WORKLOG_APPROVALS", 2L));
    }

    @Test
    void missingOrIncompleteProviderClosesAsRetryableDependencyFailure() {
        assertThatThrownBy(() -> new ProjectArchiveBlockerCollector(List.of(),
                Set.of(ProjectArchiveBlockerSource.WORKITEM)))
                .isInstanceOf(IllegalStateException.class);

        ProjectArchiveBlockerCollector collector = new ProjectArchiveBlockerCollector(
                List.of(provider(ProjectArchiveBlockerSource.WORKITEM, 0, false)),
                Set.of(ProjectArchiveBlockerSource.WORKITEM));
        assertThatThrownBy(() -> collector.collect(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOfSatisfying(ApplicationException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(StandardErrorCode.DEPENDENCY_UNAVAILABLE);
                    assertThat(error.reason()).isEqualTo("ARCHIVE_BLOCKER_DEPENDENCY_UNAVAILABLE");
                });
    }

    private static ProjectArchiveBlockerProvider provider(ProjectArchiveBlockerSource source,
            long count, boolean complete) {
        return new ProjectArchiveBlockerProvider() {
            @Override public ProjectArchiveBlockerSource source() { return source; }
            @Override public ProjectArchiveBlockerReport report(UUID companyId, UUID projectId) {
                return new ProjectArchiveBlockerReport(source, count, complete);
            }
        };
    }
}
