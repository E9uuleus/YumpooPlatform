package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.workitem.domain.Content;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ContentInitializationService {

    private final ContentRepository repository;
    private final WorkItemLabelRepository labels;
    private final Clock clock;

    public ContentInitializationService(ContentRepository repository,
            WorkItemLabelRepository labels, Clock clock) {
        this.repository = repository;
        this.labels = labels;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<InitializedContentView> initialize(ContentInitializationCommand initialization) {
        Instant now = clock.instant();
        labels.initialize(initialization.companyId(), initialization.projectId(),
                now);
        repository.initializeCatalog(initialization.companyId(), initialization.projectId(), now);
        List<Content> contents = DefaultProjectStructure.CATEGORIES.stream().map(blueprint -> {
            return Content.initial(UUID.randomUUID(), initialization.companyId(),
                    initialization.projectId(), blueprint.contentCode(), blueprint.displayName(),
                    blueprint.colorToken(), blueprint.sortOrder(), initialization.actorUserId(), now);
        }).toList();
        if (repository.insertAll(contents) != contents.size()) {
            throw new ApplicationException(StandardErrorCode.INTERNAL_ERROR);
        }
        return contents.stream().map(content -> new InitializedContentView(
                content.id(), content.code())).toList();
    }
}
