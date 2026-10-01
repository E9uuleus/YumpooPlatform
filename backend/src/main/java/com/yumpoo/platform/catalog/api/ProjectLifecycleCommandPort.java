package com.yumpoo.platform.catalog.api;

public interface ProjectLifecycleCommandPort {
    ProjectSnapshot create(ProjectCreationMutation mutation);
    ProjectSnapshot lockForArchive(ProjectArchiveMutation mutation);
    ProjectSnapshot archive(ProjectArchiveMutation mutation);
    ProjectRestoreSnapshot lockForRestore(ProjectRestoreMutation mutation);
    ProjectSnapshot reopen(ProjectRestoreMutation mutation);
    ProjectSnapshot lockForNewFact(java.util.UUID companyId, java.util.UUID projectId);
}
