package com.yumpoo.platform.workitem.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentTest {
    private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");
    private static final UUID ACTOR = UUID.fromString("29000000-0000-4000-8000-000000000001");

    @Test
    void preservesImmutableIdentityAcrossUpdateArchiveAndRestore() {
        Content initial = content();
        Content updated = initial.update("产品需求", "说明", ContentViewType.KANBAN,
                "{\"table\":{},\"kanban\":{}}", ACTOR, NOW.plusSeconds(1));
        Content archived = updated.archive(ACTOR, NOW.plusSeconds(2));
        Content restored = archived.restore(ACTOR, NOW.plusSeconds(3));

        assertThat(restored.id()).isEqualTo(initial.id());
        assertThat(restored.code()).isEqualTo("REQ_CORE");
        assertThat(restored.workItemType()).isEqualTo(ContentWorkItemType.REQUIREMENT);
        assertThat(restored.appliedTemplateKey()).isEqualTo("SOFTWARE_STANDARD");
        assertThat(restored.status()).isEqualTo(ContentStatus.ACTIVE);
        assertThat(restored.rowVersion()).isEqualTo(3);
        assertThat(restored.archivedAt()).isNull();
    }

    @Test
    void rejectsEditingArchivedContent() {
        Content archived = content().archive(ACTOR, NOW.plusSeconds(1));
        assertThatThrownBy(() -> archived.update("名称", null, ContentViewType.TABLE, "{}",
                ACTOR, NOW.plusSeconds(2))).isInstanceOf(IllegalStateException.class);
    }

    private static Content content() {
        return Content.create(UUID.fromString("29000000-0000-4000-8000-000000000002"),
                UUID.fromString("29000000-0000-4000-8000-000000000003"),
                UUID.fromString("29000000-0000-4000-8000-000000000004"),
                "REQ_CORE", "核心需求", null, ContentWorkItemType.REQUIREMENT,
                ContentViewType.TABLE, "{}", "SOFTWARE_STANDARD", 1,
                "REQUIREMENT", ACTOR, NOW);
    }
}
