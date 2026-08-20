package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.ContentViewType;
import com.yumpoo.platform.workitem.domain.ContentWorkItemType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ContentInitializationService {

    private final ContentRepository repository;
    private final Clock clock;

    public ContentInitializationService(ContentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<InitializedContentView> initialize(ContentInitializationCommand initialization) {
        if (initialization.blueprints().isEmpty()) {
            throw new ApplicationException(StandardErrorCode.VALIDATION_FAILED);
        }
        Set<String> codes = new HashSet<>();
        Set<String> types = new HashSet<>();
        Instant now = clock.instant();
        List<Content> contents = initialization.blueprints().stream().map(blueprint -> {
            if (!codes.add(blueprint.contentCode()) || !types.add(blueprint.workItemType())) {
                throw new ApplicationException(StandardErrorCode.VALIDATION_FAILED);
            }
            return Content.initial(UUID.randomUUID(), initialization.companyId(),
                    initialization.projectId(), blueprint.contentCode(), blueprint.displayName(),
                    ContentWorkItemType.valueOf(blueprint.workItemType()),
                    ContentViewType.valueOf(blueprint.defaultViewType()),
                    initialization.templateKey(), initialization.templateVersion(),
                    blueprint.contentCode(), initialization.actorUserId(), now);
        }).toList();
        if (repository.insertAll(contents) != contents.size()) {
            throw new ApplicationException(StandardErrorCode.INTERNAL_ERROR);
        }
        return contents.stream().map(content -> new InitializedContentView(
                content.id(), content.code(), content.workItemType().name())).toList();
    }
}
