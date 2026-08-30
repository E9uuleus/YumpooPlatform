package com.yumpoo.platform.audit.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActivityServiceTest {
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final Instant CUTOVER = Instant.parse("2026-08-30T08:00:00Z");

    @Test
    void emitsStableCursorAndRejectsReuseAcrossFilters() {
        ActivityRepository repository = mock(ActivityRepository.class);
        when(repository.acceptedFrom()).thenReturn(CUTOVER);
        when(repository.findScope(eq(COMPANY), eq("PROJECT"), eq(PROJECT), anySet(), anySet(),
                isNull(), isNull(), isNull(), anyInt())).thenReturn(List.of(
                        stored(Instant.parse("2026-08-30T10:00:00Z")),
                        stored(Instant.parse("2026-08-30T09:00:00Z"))));
        ActivityService service = new ActivityService(repository, new ActivityCursorCodec());

        ActivityResultPage first = service.findProject(COMPANY, PROJECT,
                new ActivityQueryCriteria(null, 1, Set.of(), Set.of(), null, null));
        assertThat(first.items()).hasSize(1);
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(first.historyStartedAt()).isEqualTo(CUTOVER);

        assertThatThrownBy(() -> service.findProject(COMPANY, PROJECT,
                new ActivityQueryCriteria(first.nextCursor(), 1,
                        Set.of("workitem.work_item_created"), Set.of(), null, null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("请求字段校验失败");
    }

    private static ActivityStoredEvent stored(Instant occurredAt) {
        return new ActivityStoredEvent(UUID.randomUUID(), UUID.randomUUID(), "PROJECT", COMPANY,
                PROJECT, "WORK_ITEM", UUID.randomUUID(), "YMP-20 投影验收",
                "workitem.work_item_created", "USER", UUID.randomUUID(), null, "林晓",
                occurredAt, "WORK_ITEM_CREATED", new ObjectMapper().createObjectNode(), 1,
                "m2-20-test", "m2-20-test", UUID.randomUUID(), null);
    }
}
