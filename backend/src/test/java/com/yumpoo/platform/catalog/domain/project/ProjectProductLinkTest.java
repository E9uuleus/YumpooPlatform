package com.yumpoo.platform.catalog.domain.project;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectProductLinkTest {

    private static final UUID ACTOR = UUID.fromString("27000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    @Test
    void primaryChangeUsesRelationVersionAndNoOpPreservesFacts() {
        ProjectProductLink link = link(false);

        assertThat(link.changePrimary(false, ACTOR, NOW.plusSeconds(1))).isSameAs(link);
        ProjectProductLink changed = link.changePrimary(true, ACTOR, NOW.plusSeconds(1));

        assertThat(changed.primary()).isTrue();
        assertThat(changed.rowVersion()).isOne();
        assertThat(changed.updatedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void removalNormalizesOptionalReasonAndKeepsPrimaryAsHistoricalFact() {
        ProjectProductLink removed = link(true).remove(ACTOR, "  业务关系结束  ", NOW.plusSeconds(2));

        assertThat(removed.status()).isEqualTo(ProjectProductLinkStatus.REMOVED);
        assertThat(removed.primary()).isTrue();
        assertThat(removed.removeReason()).isEqualTo("业务关系结束");
        assertThat(removed.rowVersion()).isOne();
        assertThatThrownBy(() -> removed.changePrimary(false, ACTOR, NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void removalReasonHasFrozenMaximumLength() {
        assertThatThrownBy(() -> link(false).remove(ACTOR, "x".repeat(501), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ProjectProductLink link(boolean primary) {
        return ProjectProductLink.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ProjectProductRelationType.DEVELOPMENT, primary, ACTOR, NOW);
    }
}
