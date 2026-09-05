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
    void preservesStableIdentityWhileEditingAndMarkingUsed() {
        Content initial = content();
        Content updated = initial.update("产品需求", "DARK_RED", 20, false,
                ACTOR, NOW.plusSeconds(1));
        Content used = updated.markUsed(ACTOR, NOW.plusSeconds(2));

        assertThat(used.id()).isEqualTo(initial.id());
        assertThat(used.code()).isEqualTo("REQ_CORE");
        assertThat(used.name()).isEqualTo("产品需求");
        assertThat(used.colorToken()).isEqualTo("DARK_RED");
        assertThat(used.active()).isFalse();
        assertThat(used.everUsed()).isTrue();
        assertThat(used.rowVersion()).isEqualTo(2);
    }

    @Test
    void protectsDefaultsAndUsedCategoriesFromDeletion() {
        assertThatThrownBy(() -> Content.initial(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "REQUIREMENTS", "需求", "BRIGHT_BLUE", 10, ACTOR, NOW)
                .delete(ACTOR, NOW.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> content().markUsed(ACTOR, NOW.plusSeconds(1))
                .delete(ACTOR, NOW.plusSeconds(2))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsUnusedCustomCategoryToBeSoftDeleted() {
        Content deleted = content().delete(ACTOR, NOW.plusSeconds(1));
        assertThat(deleted.deletedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(deleted.active()).isFalse();
    }

    @Test
    void initializationProtectsOnlyTheThreeDefaultCodes() {
        Content defaultCategory = Content.initial(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "TASKS", "任务", "BRIGHT_GREEN", 20, ACTOR, NOW);
        Content customCategory = Content.initial(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "DISCOVERY", "调研", "SKY", 40, ACTOR, NOW);

        assertThat(defaultCategory.protectedContent()).isTrue();
        assertThat(customCategory.protectedContent()).isFalse();
    }

    private static Content content() {
        return Content.create(UUID.fromString("29000000-0000-4000-8000-000000000002"),
                UUID.fromString("29000000-0000-4000-8000-000000000003"),
                UUID.fromString("29000000-0000-4000-8000-000000000004"),
                "REQ_CORE", "核心需求", "BRIGHT_BLUE", 10, ACTOR, NOW);
    }
}
