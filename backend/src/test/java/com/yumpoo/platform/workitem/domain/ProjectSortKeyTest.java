package com.yumpoo.platform.workitem.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectSortKeyTest {

    @Test
    void allocatesOneMovedItemBetweenItsVisibleNeighbors() {
        String taskA = ProjectSortKey.evenlySpaced(1, 3);
        String taskB = ProjectSortKey.evenlySpaced(2, 3);
        String movedTask = ProjectSortKey.between(taskA, taskB).orElseThrow();

        assertThat(movedTask).isBetween(taskA, taskB);
        assertThat(movedTask).hasSize(SparseRank.WIDTH);
    }

    @Test
    void locallyRebalancesAtMostOneHundredRowsInsideOuterAnchors() {
        String outsideLower = ProjectSortKey.evenlySpaced(1, 4);
        String outsideUpper = ProjectSortKey.evenlySpaced(3, 4);
        List<String> keys = new ArrayList<>();
        for (int index = 1; index <= 100; index++) {
            keys.add(ProjectSortKey.evenlySpacedBetween(
                    outsideLower, outsideUpper, index, 100).orElseThrow());
        }

        assertThat(keys).isSorted().doesNotHaveDuplicates();
        assertThat(keys.getFirst()).isGreaterThan(outsideLower);
        assertThat(keys.getLast()).isLessThan(outsideUpper);
    }
}
