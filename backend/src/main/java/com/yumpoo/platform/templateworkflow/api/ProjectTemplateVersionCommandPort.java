package com.yumpoo.platform.templateworkflow.api;

public interface ProjectTemplateVersionCommandPort {

    ProjectTemplateSnapshot publish(ProjectTemplateVersionCommand command);

    ProjectTemplateSnapshot retire(ProjectTemplateVersionCommand command);
}
