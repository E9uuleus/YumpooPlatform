package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.workitem.domain.ContentViewType;
import com.yumpoo.platform.workitem.domain.WorkItemPriority;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectWorkItemCursorCodecTest {
    private final ProjectWorkItemCursorCodec codec = new ProjectWorkItemCursorCodec();

    @Test
    void roundTripsTheCompleteImmutableSeekTuple() {
        var anchor = new WorkItemRepository.ProjectCursorAnchor(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "123456789012345678901234567890123456789", 42,
                "支持换行\n的标题", "IN_PROGRESS", WorkItemPriority.HIGH,
                null, UUID.fromString("22222222-2222-2222-2222-222222222222"),
                null, LocalDate.parse("2026-08-25"), LocalDate.parse("2026-08-26"),
                Instant.parse("2026-08-26T03:04:05Z"));
        var expected = new ProjectWorkItemCursorCodec.Cursor(
                "query-fingerprint", ContentViewType.TABLE, anchor);

        assertThat(codec.decode(codec.encode(expected))).isEqualTo(expected);
    }

    @Test
    void rejectsModifiedCursorPayloads() {
        assertThatThrownBy(() -> codec.decode("not-a-cursor"))
                .isInstanceOf(ApplicationException.class);
    }
}
