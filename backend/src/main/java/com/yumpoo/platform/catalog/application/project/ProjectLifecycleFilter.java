package com.yumpoo.platform.catalog.application.project;

public enum ProjectLifecycleFilter {
    DRAFT,
    ACTIVE,
    ARCHIVED,
    ALL;

    public boolean includeDraft() { return this == DRAFT || this == ALL; }
    public boolean includeActive() { return this == ACTIVE || this == ALL; }
    public boolean includeArchived() { return this == ARCHIVED || this == ALL; }
}
