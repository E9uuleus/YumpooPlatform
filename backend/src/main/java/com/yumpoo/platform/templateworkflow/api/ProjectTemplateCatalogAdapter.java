package com.yumpoo.platform.templateworkflow.api;

import com.yumpoo.platform.templateworkflow.application.ProjectTemplateCatalogService;
import com.yumpoo.platform.templateworkflow.application.ProjectTemplateView;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class ProjectTemplateCatalogAdapter implements
        PublishedProjectTemplateQuery,
        ProjectTemplateVersionQuery,
        ProjectTemplateVersionCommandPort {

    private final ProjectTemplateCatalogService service;

    public ProjectTemplateCatalogAdapter(ProjectTemplateCatalogService service) {
        this.service = service;
    }

    @Override
    public List<ProjectTemplateSnapshot> findAllPublished() {
        return service.findPublished().stream().map(ProjectTemplateCatalogAdapter::snapshot).toList();
    }

    @Override
    public Optional<ProjectTemplateSnapshot> findPublished(String templateKey, int version) {
        return service.findPublished(templateKey, version).map(ProjectTemplateCatalogAdapter::snapshot);
    }

    @Override
    public Optional<ProjectTemplateSnapshot> findPublishedForCreation(String templateKey, int version) {
        return service.findPublishedForCreation(templateKey, version)
                .map(ProjectTemplateCatalogAdapter::snapshot);
    }

    @Override
    public Optional<ProjectTemplateSnapshot> findAny(String templateKey, int version) {
        return service.findAny(templateKey, version).map(ProjectTemplateCatalogAdapter::snapshot);
    }

    @Override
    public ProjectTemplateSnapshot publish(ProjectTemplateVersionCommand command) {
        return snapshot(service.publish(
                command.templateKey(), command.version(), command.expectedRowVersion(),
                command.actorUserId(), command.changedAt()));
    }

    @Override
    public ProjectTemplateSnapshot retire(ProjectTemplateVersionCommand command) {
        return snapshot(service.retire(
                command.templateKey(), command.version(), command.expectedRowVersion(),
                command.actorUserId(), command.reason(), command.changedAt()));
    }

    private static ProjectTemplateSnapshot snapshot(ProjectTemplateView view) {
        return new ProjectTemplateSnapshot(
                view.templateVersionId(), view.templateKey(), view.version(), view.versionCode(),
                view.projectType(), view.displayName(), view.lifecycleStatus(), view.rowVersion(),
                view.publishedAt(), view.retiredAt(),
                view.contentBlueprints().stream().map(blueprint ->
                        new ProjectTemplateSnapshot.ContentBlueprint(
                                blueprint.contentCode(), blueprint.displayName(), blueprint.workItemType(),
                                blueprint.defaultViewType(), blueprint.sortOrder())).toList(),
                view.statuses().stream().map(status ->
                        new ProjectTemplateSnapshot.WorkflowStatus(
                                status.statusCode(), status.displayName(), status.statusCategory(),
                                status.sortOrder(), status.initial(), status.terminal())).toList(),
                view.transitions().stream().map(transition ->
                        new ProjectTemplateSnapshot.WorkflowTransition(
                                transition.fromStatus(), transition.toStatus(),
                                transition.requiredPermission(), transition.requiresResolution())).toList()
        );
    }
}
