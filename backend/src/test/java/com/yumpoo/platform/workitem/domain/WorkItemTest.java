package com.yumpoo.platform.workitem.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkItemTest {

    private static final String RANK = KanbanRank.evenlySpaced(1, 1);

    @Test
    void creationNormalizesPlainTextAndKeepsFutureFieldsClosed() {
        UUID reporter = UUID.randomUUID();
        WorkItem item = WorkItem.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 7, "PROJECT_1-7", ContentWorkItemType.TASK,
                "  修复登录失败  ", "BACKLOG", WorkItemStatusCategory.TODO,
                WorkItemPriority.MEDIUM, null, "  仅显示纯文本  ", "   ",
                null, null, null, RANK, reporter, Instant.EPOCH);

        assertThat(item.title()).isEqualTo("修复登录失败");
        assertThat(item.description()).isEqualTo("仅显示纯文本");
        assertThat(item.notes()).isNull();
        assertThat(item.assigneeUserId()).isNull();
        assertThat(item.timelineStartDate()).isNull();
        assertThat(item.rank()).isEqualTo(RANK);
        assertThat(item.rowVersion()).isZero();
    }

    @Test
    void rejectsInvalidNumberAndOversizedPlainText() {
        assertThatThrownBy(() -> WorkItem.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, "bad-1", ContentWorkItemType.DEFECT,
                "缺陷", "OPEN", WorkItemStatusCategory.TODO, WorkItemPriority.HIGH,
                null, null, null, null, null, null, RANK, UUID.randomUUID(), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itemNo");

        assertThatThrownBy(() -> WorkItem.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, "PROJECT-1", ContentWorkItemType.DEFECT,
                "缺陷", "OPEN", WorkItemStatusCategory.TODO, WorkItemPriority.HIGH,
                null, "x".repeat(16_385), null, null, null, null, RANK,
                UUID.randomUUID(), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    void fieldUpdateNormalizesBodiesAndKeepsOwnershipFactsImmutable() {
        UUID reporter = UUID.randomUUID();
        UUID assignee = UUID.randomUUID();
        WorkItem before = WorkItem.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 9, "PROJECT_1-9", ContentWorkItemType.TASK,
                "原始标题", "BACKLOG", WorkItemStatusCategory.TODO, WorkItemPriority.LOW,
                null, null, null, null, null, null, RANK, reporter, Instant.EPOCH);

        WorkItem after = before.updateFields("  新标题  ", WorkItemPriority.URGENT,
                assignee, "  描述  ", "   ", LocalDate.parse("2026-08-22"),
                LocalDate.parse("2026-08-23"), LocalDate.parse("2026-08-24"),
                reporter, Instant.EPOCH.plusSeconds(1));

        assertThat(after.title()).isEqualTo("新标题");
        assertThat(after.notes()).isNull();
        assertThat(after.assigneeUserId()).isEqualTo(assignee);
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.projectId()).isEqualTo(before.projectId());
        assertThat(after.contentId()).isEqualTo(before.contentId());
        assertThat(after.statusCode()).isEqualTo(before.statusCode());
        assertThat(after.rowVersion()).isEqualTo(before.rowVersion());
    }

    @Test
    void priorityCanBeCreatedAndUpdatedAsNull() {
        UUID actor = UUID.randomUUID();
        WorkItem created = WorkItem.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 10, "PROJECT_1-10", ContentWorkItemType.TASK,
                "暂不定优先级", "BACKLOG", WorkItemStatusCategory.TODO, null,
                null, null, null, null, null, null, RANK, actor, Instant.EPOCH);

        WorkItem cleared = created.updateFields("清空优先级", null,
                null, null, null, null, null, null,
                actor, Instant.EPOCH.plusSeconds(1));

        assertThat(created.priority()).isNull();
        assertThat(cleared.priority()).isNull();
    }

    @Test
    void rejectsInvertedTimelineOnCreateAndUpdate() {
        assertThatThrownBy(() -> WorkItem.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, "PROJECT-1", ContentWorkItemType.TASK,
                "任务", "OPEN", WorkItemStatusCategory.TODO, WorkItemPriority.MEDIUM,
                null, null, null, LocalDate.parse("2026-08-23"),
                LocalDate.parse("2026-08-22"), null, RANK, UUID.randomUUID(), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeline end");
    }

    @Test
    void statusTransitionChangesOnlyWorkflowAndAuditFacts() {
        UUID reporter = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        WorkItem before = WorkItem.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 11, "PROJECT_1-11", ContentWorkItemType.DEFECT,
                "修复并验证", "OPEN", WorkItemStatusCategory.TODO, WorkItemPriority.HIGH,
                reporter, "描述", "备注", LocalDate.parse("2026-08-23"),
                LocalDate.parse("2026-08-24"), LocalDate.parse("2026-08-25"),
                RANK, reporter, Instant.EPOCH);

        String nextRank = KanbanRank.evenlySpaced(2, 2);
        WorkItem after = before.move("DIAGNOSING",
                WorkItemStatusCategory.IN_PROGRESS, nextRank, actor, Instant.EPOCH.plusSeconds(1));

        assertThat(after.statusCode()).isEqualTo("DIAGNOSING");
        assertThat(after.statusCategory()).isEqualTo(WorkItemStatusCategory.IN_PROGRESS);
        assertThat(after.updatedByUserId()).isEqualTo(actor);
        assertThat(after.updatedAt()).isEqualTo(Instant.EPOCH.plusSeconds(1));
        assertThat(after.title()).isEqualTo(before.title());
        assertThat(after.description()).isEqualTo(before.description());
        assertThat(after.notes()).isEqualTo(before.notes());
        assertThat(after.assigneeUserId()).isEqualTo(before.assigneeUserId());
        assertThat(after.rank()).isEqualTo(nextRank);
        assertThat(after.rowVersion()).isEqualTo(before.rowVersion());
    }

    @Test
    void statusTransitionRejectsSameEndpoint() {
        WorkItem item = WorkItem.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 12, "PROJECT_1-12", ContentWorkItemType.TASK,
                "保持状态", "BACKLOG", WorkItemStatusCategory.TODO, WorkItemPriority.MEDIUM,
                null, null, null, null, null, null, RANK, UUID.randomUUID(), Instant.EPOCH);

        assertThatThrownBy(() -> item.move("BACKLOG", WorkItemStatusCategory.TODO, RANK,
                UUID.randomUUID(), Instant.EPOCH.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoints");
    }

    @Test
    void softDeleteAndRestorePreserveIdentityFieldsAndStatus() {
        UUID reporter = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        WorkItem active = WorkItem.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 15, "PROJECT_1-15", ContentWorkItemType.TASK,
                "待清理任务", "IN_PROGRESS", WorkItemStatusCategory.IN_PROGRESS,
                WorkItemPriority.HIGH, actor, "描述", "备注", null, null, null,
                RANK, reporter, Instant.EPOCH);

        WorkItem deleted = active.softDelete("  已合并到主任务  ", actor,
                Instant.EPOCH.plusSeconds(1));
        String restoredRank = KanbanRank.evenlySpaced(1, 2);
        WorkItem restored = deleted.restore(restoredRank, reporter,
                Instant.EPOCH.plusSeconds(2));

        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.deleteReason()).isEqualTo("已合并到主任务");
        assertThat(deleted.deletedByUserId()).isEqualTo(actor);
        assertThat(restored.deleted()).isFalse();
        assertThat(restored.deletedAt()).isNull();
        assertThat(restored.deleteReason()).isNull();
        assertThat(restored.id()).isEqualTo(active.id());
        assertThat(restored.itemNo()).isEqualTo(active.itemNo());
        assertThat(restored.title()).isEqualTo(active.title());
        assertThat(restored.statusCode()).isEqualTo(active.statusCode());
        assertThat(restored.rank()).isEqualTo(restoredRank);
    }

    @Test
    void deleteReasonMustContainOneToFiveHundredTrimmedCharacters() {
        WorkItem item = WorkItem.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 16, "PROJECT_1-16", ContentWorkItemType.TASK,
                "待删除任务", "BACKLOG", WorkItemStatusCategory.TODO, WorkItemPriority.LOW,
                null, null, null, null, null, null, RANK, UUID.randomUUID(), Instant.EPOCH);

        assertThatThrownBy(() -> item.softDelete("   ", UUID.randomUUID(),
                Instant.EPOCH.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deleteReason");
        assertThatThrownBy(() -> item.softDelete("删".repeat(501), UUID.randomUUID(),
                Instant.EPOCH.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deleteReason");
    }
}
