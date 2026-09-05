package com.yumpoo.platform.workitem.domain;

import com.yumpoo.platform.workitem.application.DueTimeChange;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkItemDeadlineTest {
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final LocalDate DATE = LocalDate.parse("2026-09-03");
    private static final LocalTime TIME = LocalTime.of(18, 5);
    private static final String RANK = KanbanRank.evenlySpaced(1, 1);

    private WorkItem item() {
        return WorkItem.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "DEADLINE-1", "截止时间测试", "NOT_STARTED",
                WorkItemStatusCategory.TODO, null, null, null, null, null, null, DATE,
                TIME, RANK, ProjectSortKey.evenlySpaced(1, 1), ACTOR, START);
    }

    @Test
    void recordsOnlyTheCurrentDonePeriodAndPreservesItAcrossOtherMutations() {
        WorkItem original = item();
        assertThat(original.completedAt()).isNull();
        Instant completed = START.plusSeconds(60);
        WorkItem done = original.move("DONE", WorkItemStatusCategory.DONE, RANK, ACTOR, completed);
        assertThat(done.completedAt()).isEqualTo(completed);
        WorkItem reviewed = done.move("REVIEWED", WorkItemStatusCategory.DONE, RANK, ACTOR, START.plusSeconds(120));
        assertThat(reviewed.completedAt()).isEqualTo(completed);
        WorkItem edited = reviewed.updateFields("新标题", "HIGH", null, null, null, null, null,
                DATE.plusDays(1), ACTOR, START.plusSeconds(180));
        WorkItem moved = edited.changeContent(UUID.randomUUID(), ACTOR, START.plusSeconds(190))
                .reorder(RANK, ACTOR, START.plusSeconds(200))
                .reorderProject(ProjectSortKey.evenlySpaced(1, 2), ACTOR)
                .softDelete("测试", ACTOR, START.plusSeconds(220))
                .restore(RANK, ACTOR, START.plusSeconds(240));
        assertThat(moved.dueTime()).isEqualTo(TIME);
        assertThat(moved.completedAt()).isEqualTo(completed);
        WorkItem reopened = moved.move("NOT_STARTED", WorkItemStatusCategory.TODO, RANK, ACTOR, START.plusSeconds(300));
        assertThat(reopened.completedAt()).isNull();
        assertThat(reopened.move("DONE", WorkItemStatusCategory.DONE, RANK, ACTOR, START.plusSeconds(360)).completedAt())
                .isEqualTo(START.plusSeconds(360));
    }

    @Test
    void distinguishesOmittedTimeFromExplicitClearAndClearsTimeWithDate() {
        assertThat(DueTimeChange.unchanged().resolve(DATE, TIME)).isEqualTo(TIME);
        assertThat(new DueTimeChange(true, null).resolve(DATE, TIME)).isNull();
        assertThat(DueTimeChange.unchanged().resolve(null, TIME)).isNull();
        assertThatThrownBy(() -> new DueTimeChange(true, TIME).resolve(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        WorkItem cleared = item().updateFields("截止时间测试", null, null, null, null,
                null, null, null, ACTOR, START.plusSeconds(1));
        assertThat(cleared.dueDate()).isNull();
        assertThat(cleared.dueTime()).isNull();
    }

    @Test
    void rejectsTimesWithoutDateOrWithSeconds() {
        WorkItem item = item();
        assertThatThrownBy(() -> item.updateFields(item.title(), null, null, null, null,
                null, null, null, TIME, ACTOR, START.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item.updateFields(item.title(), null, null, null, null,
                null, null, DATE, TIME.plusSeconds(1), ACTOR, START.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
