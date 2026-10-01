package com.yumpoo.platform.workitem.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectColumnTest {
    @Test
    void normalizesNamesAndTargetsWithoutMutatingIdentityOrCreationFacts() {
        UUID project = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        Instant now = Instant.now();
        var column = ConnectColumn.create(UUID.randomUUID(), UUID.randomUUID(), project, "  DEFECTS  ",
                List.of(target, target), actor, now);
        assertThat(column.name()).isEqualTo("DEFECTS");
        assertThat(column.normalizedName()).isEqualTo("defects");
        assertThat(column.targetProjectIds()).containsExactly(target);
        var updated = column.update("缺陷", List.of(target), actor, now.plusSeconds(1));
        assertThat(updated.id()).isEqualTo(column.id());
        assertThat(updated.createdAt()).isEqualTo(now);
        assertThat(updated.rowVersion()).isEqualTo(1);
        assertThat(updated.delete(actor, now.plusSeconds(2)).active()).isFalse();
    }

    @Test
    void rejectsReservedNamesInvalidTargetCountsAndSelfTargets() {
        UUID project = UUID.randomUUID();
        assertThatThrownBy(() -> ConnectColumn.normalizeName("被连接")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectColumn.normalizeName(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectColumn.normalizeName("a".repeat(41))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectColumn.normalizeTargets(project, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectColumn.normalizeTargets(project, List.of(project))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectColumn.normalizeTargets(project,
                java.util.stream.Stream.generate(UUID::randomUUID).limit(21).toList())).isInstanceOf(IllegalArgumentException.class);
    }
}
