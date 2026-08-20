package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.workitem.application.ContentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class ProjectContentReadinessAdapter implements ProjectContentReadinessQuery {
    private final ContentRepository repository;

    public ProjectContentReadinessAdapter(ContentRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveContent(UUID companyId, UUID projectId, String templateKey,
                                    int templateVersion) {
        return repository.hasActiveForTemplate(companyId, projectId, templateKey, templateVersion);
    }
}
