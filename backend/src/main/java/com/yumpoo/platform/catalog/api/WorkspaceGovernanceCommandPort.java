package com.yumpoo.platform.catalog.api;

public interface WorkspaceGovernanceCommandPort {
    WorkspaceGovernanceSnapshot lockForArchiveOverride(WorkspaceGovernanceMutation mutation);
    WorkspaceGovernanceSnapshot archiveOverride(WorkspaceGovernanceMutation mutation);
}
