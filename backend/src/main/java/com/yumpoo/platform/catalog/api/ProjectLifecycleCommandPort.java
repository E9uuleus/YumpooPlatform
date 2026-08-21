package com.yumpoo.platform.catalog.api;

public interface ProjectLifecycleCommandPort {
    ProjectSnapshot create(ProjectCreationMutation mutation);
    ProjectActivationSnapshot lockForActivation(ProjectActivationMutation mutation);
    ProjectSnapshot activate(ProjectActivationMutation mutation);
    ProjectSnapshot lockForArchive(ProjectArchiveMutation mutation);
    ProjectSnapshot archive(ProjectArchiveMutation mutation);
    ProjectRestoreSnapshot lockForRestore(ProjectRestoreMutation mutation);
    ProjectSnapshot reopen(ProjectRestoreMutation mutation);
    ProjectSnapshot moveWorkspace(ProjectWorkspaceMoveMutation mutation);
    ProjectSnapshot lockForWorkspaceMove(ProjectWorkspaceMoveMutation mutation);
    ProjectSnapshot lockForNewFact(java.util.UUID companyId, java.util.UUID projectId);
}
