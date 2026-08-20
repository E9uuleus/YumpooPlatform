package com.yumpoo.platform.templateworkflow.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.templateworkflow.domain.InvalidProjectTemplateDefinitionException;
import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition;
import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinitionValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProjectTemplateCatalogService {

    private final ProjectTemplateRepository repository;
    private final ProjectTemplateDefinitionValidator validator = new ProjectTemplateDefinitionValidator();

    public ProjectTemplateCatalogService(ProjectTemplateRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ProjectTemplateView> findPublished() {
        return repository.findPublished().stream().map(ProjectTemplateCatalogService::view).toList();
    }

    @Transactional(readOnly = true)
    public Optional<ProjectTemplateView> findPublished(String templateKey, int version) {
        return repository.find(templateKey, version, false)
                .filter(definition -> definition.lifecycle() == ProjectTemplateDefinition.Lifecycle.PUBLISHED)
                .map(ProjectTemplateCatalogService::view);
    }

    @Transactional
    public Optional<ProjectTemplateView> findPublishedForCreation(String templateKey, int version) {
        return repository.findForShare(templateKey, version)
                .filter(definition -> definition.lifecycle() == ProjectTemplateDefinition.Lifecycle.PUBLISHED)
                .map(ProjectTemplateCatalogService::view);
    }

    @Transactional(readOnly = true)
    public Optional<ProjectTemplateView> findAny(String templateKey, int version) {
        return repository.find(templateKey, version, false).map(ProjectTemplateCatalogService::view);
    }

    @Transactional
    public ProjectTemplateView publish(
            String templateKey,
            int version,
            long expectedRowVersion,
            UUID actorUserId,
            Instant changedAt
    ) {
        ProjectTemplateDefinition definition = requireLocked(templateKey, version);
        requireVersion(definition, expectedRowVersion);
        if (definition.lifecycle() != ProjectTemplateDefinition.Lifecycle.DRAFT) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        try {
            validator.validateForPublish(definition);
        } catch (InvalidProjectTemplateDefinitionException exception) {
            throw new ApplicationException(
                    StandardErrorCode.VALIDATION_FAILED,
                    "模板定义不完整，不能发布",
                    List.of(new FieldViolation(
                            "templateDefinition", "INVALID_TEMPLATE_DEFINITION", exception.getMessage()))
            );
        }
        if (!repository.publish(definition.id(), expectedRowVersion, actorUserId, changedAt)) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
        return repository.find(templateKey, version, false)
                .map(ProjectTemplateCatalogService::view)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.INTERNAL_ERROR));
    }

    @Transactional
    public ProjectTemplateView retire(
            String templateKey,
            int version,
            long expectedRowVersion,
            UUID actorUserId,
            String reason,
            Instant changedAt
    ) {
        ProjectTemplateDefinition definition = requireLocked(templateKey, version);
        requireVersion(definition, expectedRowVersion);
        if (definition.lifecycle() != ProjectTemplateDefinition.Lifecycle.PUBLISHED) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        if (!repository.retire(definition.id(), expectedRowVersion, actorUserId, reason, changedAt)) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
        return repository.find(templateKey, version, false)
                .map(ProjectTemplateCatalogService::view)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.INTERNAL_ERROR));
    }

    private ProjectTemplateDefinition requireLocked(String templateKey, int version) {
        return repository.find(templateKey, version, true)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private static void requireVersion(ProjectTemplateDefinition definition, long expectedRowVersion) {
        if (definition.rowVersion() != expectedRowVersion) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
    }

    private static ProjectTemplateView view(ProjectTemplateDefinition definition) {
        return new ProjectTemplateView(
                definition.id(), definition.templateKey().name(), definition.version(),
                definition.versionCode(), definition.projectType().name(), definition.displayName(),
                definition.lifecycle().name(), definition.rowVersion(), definition.publishedAt(),
                definition.retiredAt(),
                definition.contentBlueprints().stream().map(blueprint ->
                        new ProjectTemplateView.ContentBlueprintView(
                                blueprint.contentCode(), blueprint.displayName(),
                                blueprint.workItemType().name(), blueprint.defaultViewType().name(),
                                blueprint.sortOrder())).toList(),
                definition.statuses().stream().map(status ->
                        new ProjectTemplateView.WorkflowStatusView(
                                status.statusCode(), status.displayName(), status.statusCategory().name(),
                                status.sortOrder(), status.initial(), status.terminal())).toList(),
                definition.transitions().stream().map(transition ->
                        new ProjectTemplateView.WorkflowTransitionView(
                                transition.fromStatus(), transition.toStatus(),
                                transition.requiredPermission().name(),
                                transition.requiresResolution())).toList()
        );
    }
}
