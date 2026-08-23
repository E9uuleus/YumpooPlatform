package com.yumpoo.platform.workitem.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KanbanRankTest {

    @Test
    void allocatesLexicographicallyOrderedMidpointsAndEdges() {
        String first = KanbanRank.evenlySpaced(1, 3);
        String second = KanbanRank.evenlySpaced(2, 3);
        String third = KanbanRank.evenlySpaced(3, 3);

        assertThat(first.compareTo(second)).isNegative();
        assertThat(second.compareTo(third)).isNegative();
        assertThat(KanbanRank.between(null, first)).isPresent()
                .get().satisfies(value -> assertThat(value.compareTo(first)).isNegative());
        assertThat(KanbanRank.between(first, second)).isPresent()
                .get().satisfies(value -> {
                    assertThat(value.compareTo(first)).isPositive();
                    assertThat(value.compareTo(second)).isNegative();
                });
        assertThat(KanbanRank.between(third, null)).isPresent()
                .get().satisfies(value -> assertThat(value.compareTo(third)).isPositive());
    }

    @Test
    void reportsExhaustedIntegerGapForLaneRebalancing() {
        String left = "000000000000000000000000000000000000001";
        String right = "000000000000000000000000000000000000002";

        assertThat(KanbanRank.between(left, right)).isEmpty();
    }

    @Test
    void rejectsMalformedAndSentinelRanks() {
        assertThatThrownBy(() -> KanbanRank.require("123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KanbanRank.require("0".repeat(KanbanRank.WIDTH)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
