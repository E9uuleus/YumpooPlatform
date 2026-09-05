package com.yumpoo.platform.audit.api;

import com.yumpoo.platform.audit.application.WorkItemCellActivityCursorCodec;
import com.yumpoo.platform.audit.application.WorkItemCellActivityRepository;
import com.yumpoo.platform.audit.application.WorkItemCellActivityStoredEvent;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkItemCellActivityQueryAdapterTest {
    private static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("46100000-0000-4000-8000-000000000001");
    private static final UUID ITEM = UUID.fromString("46100000-0000-4000-8000-000000000002");
    private static final UUID ACTOR = UUID.fromString("46100000-0000-4000-8000-000000000003");
    private static final UUID CONTENT = UUID.fromString("46100000-0000-4000-8000-000000000004");
    private static final Instant NOW = Instant.parse("2026-09-01T16:30:00Z");
    private static final Instant CUTOVER = Instant.parse("2026-09-01T08:00:00Z");

    private WorkItemCellActivityRepository repository;
    private WorkItemCellActivityQueryAdapter adapter;

    @BeforeEach
    void setUp() {
        repository = mock(WorkItemCellActivityRepository.class);
        when(repository.acceptedFrom()).thenReturn(CUTOVER);
        when(repository.findForFacets(eq(COMPANY), eq(ITEM), any())).thenReturn(List.of());
        adapter = new WorkItemCellActivityQueryAdapter(repository,
                new WorkItemCellActivityCursorCodec(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void todayUsesCompanyTimezoneAndReturnsFixedFiveTimeFacets() {
        when(repository.find(eq(COMPANY), eq(ITEM), any(), any(), anyInt()))
                .thenReturn(List.of(row(Instant.parse("2026-09-01T16:15:00Z"), "STATUS")));

        WorkItemCellActivityPage page = adapter.find(COMPANY, PROJECT, ITEM,
                ZoneId.of("Asia/Shanghai"), DayOfWeek.MONDAY,
                new WorkItemCellActivityQuery(null, 25, WorkItemCellActivityTimeRange.TODAY,
                        null, null));

        ArgumentCaptor<WorkItemCellActivityRepository.Filters> filters =
                ArgumentCaptor.forClass(WorkItemCellActivityRepository.Filters.class);
        org.mockito.Mockito.verify(repository).find(eq(COMPANY), eq(ITEM), filters.capture(),
                eq(null), eq(26));
        assertThat(filters.getValue().occurredFrom()).isEqualTo("2026-09-01T16:00:00Z");
        assertThat(filters.getValue().occurredTo()).isEqualTo("2026-09-02T16:00:00Z");
        assertThat(page.items()).hasSize(1);
        assertThat(page.facets().timeRanges()).hasSize(5);
    }

    @Test
    void cursorBindsFiltersAndInitialTimeAnchor() {
        when(repository.find(eq(COMPANY), eq(ITEM), any(), any(), anyInt()))
                .thenReturn(List.of(row(NOW.minusSeconds(30), "STATUS"),
                        row(NOW.minusSeconds(60), "PRIORITY")));
        WorkItemCellActivityPage first = adapter.find(COMPANY, PROJECT, ITEM,
                ZoneId.of("Asia/Shanghai"), DayOfWeek.MONDAY,
                new WorkItemCellActivityQuery(null, 1, null, null,
                        List.of(WorkItemCellActivityColumn.STATUS)));
        assertThat(first.nextCursor()).isNotBlank();

        assertThatThrownBy(() -> adapter.find(COMPANY, PROJECT, ITEM,
                ZoneId.of("Asia/Shanghai"), DayOfWeek.MONDAY,
                new WorkItemCellActivityQuery(first.nextCursor(), 1, null, null,
                        List.of(WorkItemCellActivityColumn.PRIORITY))))
                .isInstanceOf(ApplicationException.class);
    }

    private WorkItemCellActivityStoredEvent row(Instant occurredAt, String column) {
        ObjectNode value = new ObjectMapper().createObjectNode();
        value.put("type", "LABEL"); value.put("referenceId", "CODE");
        value.put("displayName", "展示值"); value.put("colorToken", "BRIGHT_BLUE");
        return new WorkItemCellActivityStoredEvent(UUID.randomUUID(), UUID.randomUUID(), COMPANY,
                PROJECT, ITEM, CONTENT, "任务", "workitem.test", column, "CHANGED", value,
                value, "USER", ACTOR, null, "林晓", occurredAt, "query-test", "query-test");
    }
}
