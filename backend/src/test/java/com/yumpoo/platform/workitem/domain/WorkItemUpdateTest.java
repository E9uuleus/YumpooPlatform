package com.yumpoo.platform.workitem.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkItemUpdateTest {
    private static final Instant CREATED = Instant.parse("2026-08-24T10:00:00Z");
    private static final UUID AUTHOR = UUID.fromString("36000000-0000-4000-8000-000000000001");

    @Test
    void editsOnlyBeforeDeadlineAndKeepsNoopStable() {
        WorkItemUpdate published = published();
        assertThat(published.edit("<p>原文</p>", "原文", AUTHOR, CREATED.plusSeconds(600)))
                .isSameAs(published);

        WorkItemUpdate edited = published.edit("<p>新正文</p>", "新正文", AUTHOR,
                CREATED.plusSeconds(899));
        assertThat(edited.status()).isEqualTo(WorkItemUpdateStatus.EDITED);
        assertThat(edited.rowVersion()).isOne();
        assertThat(edited.editedByUserId()).isEqualTo(AUTHOR);
        assertThat(edited.editDeadlineAt()).isEqualTo(published.editDeadlineAt());

        assertThatThrownBy(() -> published.edit("<p>超时</p>", "超时", AUTHOR,
                CREATED.plusSeconds(900)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("UPDATE_EDIT_WINDOW_EXPIRED");
    }

    @Test
    void selfDeleteHasNoReasonAndCannotRunAtDeadline() {
        WorkItemUpdate deleted = published().selfDelete(AUTHOR, CREATED.plusSeconds(899));
        assertThat(deleted.status()).isEqualTo(WorkItemUpdateStatus.DELETED);
        assertThat(deleted.bodyHtml()).isNull();
        assertThat(deleted.deleteReason()).isNull();
        assertThat(deleted.deletedByUserId()).isEqualTo(AUTHOR);

        assertThatThrownBy(() -> published().selfDelete(AUTHOR, CREATED.plusSeconds(900)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("UPDATE_SELF_DELETE_WINDOW_EXPIRED");
    }

    @Test
    void moderationCanDeleteAfterDeadlineAndNormalizesReason() {
        UUID owner = UUID.fromString("36000000-0000-4000-8000-000000000002");
        WorkItemUpdate deleted = published().moderateDelete(owner, "  包含敏感信息  ",
                CREATED.plusSeconds(3_600));
        assertThat(deleted.status()).isEqualTo(WorkItemUpdateStatus.DELETED);
        assertThat(deleted.deletedByUserId()).isEqualTo(owner);
        assertThat(deleted.deleteReason()).isEqualTo("包含敏感信息");
        assertThatThrownBy(() -> deleted.edit("<p>恢复</p>", "恢复", AUTHOR,
                CREATED.plusSeconds(100)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("UPDATE_ALREADY_DELETED");
    }

    private static WorkItemUpdate published() {
        return WorkItemUpdate.published(
                UUID.fromString("36000000-0000-4000-8000-000000000010"),
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                UUID.fromString("36000000-0000-4000-8000-000000000011"),
                UUID.fromString("36000000-0000-4000-8000-000000000012"),
                UUID.fromString("36000000-0000-4000-8000-000000000013"),
                AUTHOR, "作者", "<p>原文</p>", "原文", CREATED);
    }
}
