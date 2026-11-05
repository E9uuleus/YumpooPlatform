package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.workitem.application.content.ContentInitializationCommand;
import com.yumpoo.platform.workitem.application.content.ContentInitializationService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ProjectContentInitializationAdapter implements InitializeProjectContentsPort {

    private final ContentInitializationService service;

    public ProjectContentInitializationAdapter(ContentInitializationService service) {
        this.service = service;
    }

    @Override
    public List<InitializedProjectContent> initialize(ProjectContentInitialization initialization) {
        ContentInitializationCommand command = new ContentInitializationCommand(
                initialization.companyId(),
                initialization.projectId(),
                initialization.actorUserId(),
                initialization.colors().categories(),
                initialization.colors().statuses(),
                initialization.colors().priorities());
        return service.initialize(command).stream()
                .map(content -> new InitializedProjectContent(content.contentId(), content.code()))
                .toList();
    }
}
