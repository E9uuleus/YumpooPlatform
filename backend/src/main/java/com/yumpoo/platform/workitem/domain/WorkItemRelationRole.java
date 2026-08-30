package com.yumpoo.platform.workitem.domain;

public enum WorkItemRelationRole {
    PARENT(WorkItemRelationType.PARENT_CHILD, true),
    CHILD(WorkItemRelationType.PARENT_CHILD, false),
    RELATED(WorkItemRelationType.RELATED, true),
    BLOCKS(WorkItemRelationType.BLOCKS, true),
    BLOCKED_BY(WorkItemRelationType.BLOCKS, false),
    SOURCE(WorkItemRelationType.SOURCE, true),
    DERIVED_FROM(WorkItemRelationType.SOURCE, false),
    DUPLICATE_OF(WorkItemRelationType.DUPLICATE, true),
    CANONICAL(WorkItemRelationType.DUPLICATE, false);

    private final WorkItemRelationType relationType;
    private final boolean leftSide;

    WorkItemRelationRole(WorkItemRelationType relationType, boolean leftSide) {
        this.relationType = relationType;
        this.leftSide = leftSide;
    }

    public WorkItemRelationType relationType() { return relationType; }
    public boolean leftSide() { return leftSide; }

    public WorkItemRelationRole counterpart() {
        return switch (this) {
            case PARENT -> CHILD;
            case CHILD -> PARENT;
            case RELATED -> RELATED;
            case BLOCKS -> BLOCKED_BY;
            case BLOCKED_BY -> BLOCKS;
            case SOURCE -> DERIVED_FROM;
            case DERIVED_FROM -> SOURCE;
            case DUPLICATE_OF -> CANONICAL;
            case CANONICAL -> DUPLICATE_OF;
        };
    }
}
