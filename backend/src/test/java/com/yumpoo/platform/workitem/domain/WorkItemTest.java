package com.yumpoo.platform.workitem.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkItemTest {

    @Test
    void creationNormalizesPlainTextAndKeepsFutureFieldsClosed() {
        UUID reporter = UUID.randomUUID();
        WorkItem item = WorkItem.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 7, "PROJECT_1-7", ContentWorkItemType.TASK,
                "  修复登录失败  ", "BACKLOG", WorkItemStatusCategory.TODO,
                WorkItemPriority.MEDIUM, "  仅显示纯文本  ", "   ", reporter, Instant.EPOCH);

        assertThat(item.title()).isEqualTo("修复登录失败");
        assertThat(item.description()).isEqualTo("仅显示纯文本");
        assertThat(item.notes()).isNull();
        assertThat(item.assigneeUserId()).isNull();
        assertThat(item.timelineStartDate()).isNull();
        assertThat(item.rank()).isNull();
        assertThat(item.rowVersion()).isZero();
    }

    @Test
    void rejectsInvalidNumberAndOversizedPlainText() {
        assertThatThrownBy(() -> WorkItem.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, "bad-1", ContentWorkItemType.DEFECT,
                "缺陷", "OPEN", WorkItemStatusCategory.TODO, WorkItemPriority.HIGH,
                null, null, UUID.randomUUID(), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itemNo");

        assertThatThrownBy(() -> WorkItem.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, "PROJECT-1", ContentWorkItemType.DEFECT,
                "缺陷", "OPEN", WorkItemStatusCategory.TODO, WorkItemPriority.HIGH,
                "x".repeat(16_385), null, UUID.randomUUID(), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }
}
