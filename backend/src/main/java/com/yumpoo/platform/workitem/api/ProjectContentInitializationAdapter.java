package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.workitem.application.ContentInitializationService;
import com.yumpoo.platform.workitem.application.ContentInitializationCommand;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProjectContentInitializationAdapter implements InitializeProjectContentsPort {

    private final ContentInitializationService service;

    public ProjectContentInitializationAdapter(ContentInitializationService service) {
        this.service = service;
    }

    @Override
    public List<InitializedProjectContent> initialize(ProjectContentInitialization initialization) {
        ContentInitializationCommand command = new ContentInitializationCommand(
                initialization.companyId(), initialization.projectId(), initialization.actorUserId(),
                initialization.colors().categories(), initialization.colors().statuses(), initialization.colors().priorities());
        return service.initialize(command).stream()
                .map(content -> new InitializedProjectContent(
                        content.contentId(), content.code()))
                .toList();
    }
}
