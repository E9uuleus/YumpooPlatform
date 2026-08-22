package com.yumpoo.platform.administration.infrastructure;

import com.yumpoo.platform.administration.application.ProjectArchiveBlockerProvider;
import com.yumpoo.platform.administration.application.ProjectArchiveBlockerReport;
import com.yumpoo.platform.administration.application.ProjectArchiveBlockerSource;
import com.yumpoo.platform.workitem.api.WorkItemProjectArchiveBlockerPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public final class WorkItemArchiveBlockerProvider implements ProjectArchiveBlockerProvider {
    private final WorkItemProjectArchiveBlockerPort workItems;

    public WorkItemArchiveBlockerProvider(WorkItemProjectArchiveBlockerPort workItems) {
        this.workItems = workItems;
    }

    @Override
    public ProjectArchiveBlockerSource source() {
        return ProjectArchiveBlockerSource.WORKITEM;
    }

    @Override
    public ProjectArchiveBlockerReport report(UUID companyId, UUID projectId) {
        return new ProjectArchiveBlockerReport(source(), workItems.countOpen(companyId, projectId), true);
    }
}
