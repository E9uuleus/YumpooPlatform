package com.yumpoo.platform.catalog.api;

public interface ProjectLifecycleCommandPort {
    ProjectSnapshot create(ProjectCreationMutation mutation);
}
