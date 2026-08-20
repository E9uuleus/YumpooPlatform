package com.yumpoo.platform.catalog.api;

public interface ProjectLifecycleCommandPort {
    ProjectSnapshot create(ProjectCreationMutation mutation);
    ProjectActivationSnapshot lockForActivation(ProjectActivationMutation mutation);
    ProjectSnapshot activate(ProjectActivationMutation mutation);
}
