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
    void editsWithoutDeadlineAndKeepsNoopStable() {
        WorkItemUpdate published = published();
        assertThat(published.edit("<p>原文</p>", "原文", AUTHOR, CREATED.plusSeconds(86_400))).isSameAs(published);
        WorkItemUpdate edited = published.edit("<p>新正文</p>", "新正文", AUTHOR, CREATED.plusSeconds(86_400));
        assertThat(edited.status()).isEqualTo(WorkItemUpdateStatus.EDITED);
        assertThat(edited.rowVersion()).isOne();
        assertThatThrownBy(() -> published.edit("<p>越权</p>", "越权", UUID.randomUUID(), CREATED.plusSeconds(10)))
                .hasMessage("UPDATE_EDIT_FORBIDDEN");
    }

    @Test
    void deleteClearsBodyAndPinWithoutDeadline() {
        WorkItemUpdate pinned = published().pin(true, AUTHOR, CREATED.plusSeconds(10));
        assertThat(pinned.pin(true, UUID.randomUUID(), CREATED.plusSeconds(20))).isSameAs(pinned);
        WorkItemUpdate deleted = pinned.delete(AUTHOR, CREATED.plusSeconds(86_400));
        assertThat(deleted.bodyHtml()).isNull();
        assertThat(deleted.bodyText()).isNull();
        assertThat(deleted.pinnedAt()).isNull();
        assertThat(deleted.pinnedByUserId()).isNull();
        assertThatThrownBy(() -> deleted.edit("<p>恢复</p>", "恢复", AUTHOR, CREATED.plusSeconds(86_401)))
                .hasMessage("UPDATE_ALREADY_DELETED");
    }

    @Test
    void repliesCannotBePinned() {
        WorkItemUpdate root = published();
        WorkItemUpdate reply = WorkItemUpdate.published(UUID.randomUUID(), root.companyId(), root.projectId(),
                root.contentId(), root.workItemId(), AUTHOR, "作者", "<p>回复</p>", "回复", CREATED, root.id());
        assertThatThrownBy(() -> reply.pin(true, AUTHOR, CREATED.plusSeconds(1))).hasMessage("UPDATE_REPLY_PIN_FORBIDDEN");
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
