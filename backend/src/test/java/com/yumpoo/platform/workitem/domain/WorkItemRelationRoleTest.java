package com.yumpoo.platform.workitem.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkItemRelationRoleTest {
    @Test
    void mapsEveryBusinessRoleToItsTypeSideAndCounterpart() {
        assertThat(WorkItemRelationRole.PARENT.relationType())
                .isEqualTo(WorkItemRelationType.PARENT_CHILD);
        assertThat(WorkItemRelationRole.PARENT.leftSide()).isTrue();
        assertThat(WorkItemRelationRole.PARENT.counterpart()).isEqualTo(WorkItemRelationRole.CHILD);
        assertThat(WorkItemRelationRole.RELATED.counterpart()).isEqualTo(WorkItemRelationRole.RELATED);
        assertThat(WorkItemRelationRole.BLOCKS.counterpart()).isEqualTo(WorkItemRelationRole.BLOCKED_BY);
        assertThat(WorkItemRelationRole.SOURCE.counterpart()).isEqualTo(WorkItemRelationRole.DERIVED_FROM);
        assertThat(WorkItemRelationRole.DUPLICATE_OF.counterpart()).isEqualTo(WorkItemRelationRole.CANONICAL);
        assertThat(WorkItemRelationRole.CHILD.leftSide()).isFalse();
        assertThat(WorkItemRelationRole.BLOCKED_BY.leftSide()).isFalse();
        assertThat(WorkItemRelationRole.DERIVED_FROM.leftSide()).isFalse();
        assertThat(WorkItemRelationRole.CANONICAL.leftSide()).isFalse();
    }
}
