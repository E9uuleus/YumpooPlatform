package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.catalog.api.ProjectFactWriteGuard;
import com.yumpoo.platform.catalog.api.ProjectFactWriteSnapshot;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyCommand;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyScope;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateSnapshot;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateVersionQuery;
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.ContentStatus;
import com.yumpoo.platform.workitem.domain.ContentViewType;
import com.yumpoo.platform.workitem.domain.ContentWorkItemType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.ContentCommands.*;
import static com.yumpoo.platform.workitem.application.ContentModels.*;

@Service
public class ContentService {
    private static final String CREATED = "workitem.content_created";
    private static final String UPDATED = "workitem.content_updated";
    private static final String ARCHIVED = "workitem.content_archived";
    private static final String RESTORED = "workitem.content_restored";

    private final ContentRepository contents;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectFactWriteGuard writeGuard;
    private final ProjectTemplateVersionQuery templates;
    private final ContentViewConfigCodec configs;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ContentService(ContentRepository contents, ProjectAccessSnapshotQuery access,
            ProjectFactWriteGuard writeGuard, ProjectTemplateVersionQuery templates,
            ContentViewConfigCodec configs, IdempotentCommandExecutor idempotency,
            TransactionalEventPort events, ObjectMapper objectMapper, Clock clock) {
        this.contents = contents; this.access = access; this.writeGuard = writeGuard;
        this.templates = templates; this.configs = configs; this.idempotency = idempotency;
        this.events = events; this.objectMapper = objectMapper; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProjectContentCatalog catalog(CurrentActor actor, UUID projectId) {
        ProjectAccessSnapshot project = visible(actor, projectId);
        ProjectTemplateSnapshot template = template(project.templateKey(), project.templateVersion());
        List<ContentView> items = contents.findAll(project.companyId(), projectId).stream()
                .map(content -> view(content, template)).toList();
        return new ProjectContentCatalog(items, template.contentBlueprints().stream()
                .sorted(java.util.Comparator.comparingInt(ProjectTemplateSnapshot.ContentBlueprint::sortOrder))
                .map(value -> new BlueprintOption(value.contentCode(), value.displayName(),
                        value.workItemType(), value.defaultViewType())).toList(),
                statusOptions(template), project.actorAccess() == ProjectAccessSnapshot.ActorProjectAccess.OWNER
                        && project.lifecycle() != ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED);
    }

    @Transactional(readOnly = true)
    public ContentView find(CurrentActor actor, UUID contentId) {
        ContentLocator locator = locator(actor, contentId);
        ProjectAccessSnapshot project = visible(actor, locator.projectId());
        Content content = contents.find(project.companyId(), locator.projectId(), contentId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        return view(content, template(project.templateKey(), project.templateVersion()));
    }

    public IdempotencyExecutionResult create(Create command) {
        requireOwner(visible(command.actor(), command.projectId()));
        return idempotency.execute(idempotency(command.actor(), "createContent",
                command.idempotencyKey(), command.requestHash()), () -> {
            ProjectFactWriteSnapshot project = writable(command.actor(), command.projectId());
            ProjectTemplateSnapshot template = template(project.templateKey(), project.templateVersion());
            ProjectTemplateSnapshot.ContentBlueprint blueprint = template.contentBlueprints().stream()
                    .filter(value -> value.contentCode().equals(command.blueprintCode())).findFirst()
                    .orElseThrow(() -> validation("blueprintCode", "UNKNOWN_BLUEPRINT",
                            "蓝图必须属于 Project 固定模板"));
            ContentViewConfig config = configs.normalize(objectMapper.createObjectNode(), template.statuses());
            Content content = Content.create(UUID.randomUUID(), project.companyId(), project.projectId(),
                    command.code(), command.name(), command.description(),
                    ContentWorkItemType.valueOf(blueprint.workItemType()),
                    ContentViewType.valueOf(blueprint.defaultViewType()), configs.write(config),
                    project.templateKey(), project.templateVersion(), blueprint.contentCode(),
                    command.actor().userId(), clock.instant());
            if (!contents.insert(content)) throw validation("code", "DUPLICATE",
                    "同一 Project 内 Content 代码不可重复");
            append(CREATED, content, command.actor(), List.of());
            return stored(201, view(content, template));
        });
    }

    @Transactional
    public ContentView update(Update command) {
        ContentLocator locator = locator(command.actor(), command.contentId());
        ProjectFactWriteSnapshot project = writable(command.actor(), locator.projectId());
        Content before = locked(project, command.contentId());
        requireVersion(before, command.expectedVersion());
        requireStatus(before, ContentStatus.ACTIVE);
        ProjectTemplateSnapshot template = template(project.templateKey(), project.templateVersion());
        String canonicalBefore = configs.write(configs.read(before.viewConfigJson(), template.statuses()));
        String canonicalAfter = configs.write(configs.normalize(command.viewConfig(), template.statuses()));
        Content candidate = before.update(command.name(), command.description(),
                viewType(command.defaultViewType()),
                canonicalAfter, command.actor().userId(), clock.instant());
        List<String> changed = changed(before, candidate, canonicalBefore, canonicalAfter);
        if (changed.isEmpty()) return view(before, template);
        Content after = contents.update(candidate, command.expectedVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        append(UPDATED, after, command.actor(), changed);
        return view(after, template);
    }

    public IdempotencyExecutionResult archive(Transition command) {
        ContentLocator locator = requireOwnerVisible(command.actor(), command.contentId());
        return idempotency.execute(idempotency(command.actor(), "archiveContent",
                command.idempotencyKey(), command.requestHash()), () -> transition(
                command, locator.projectId(), ContentStatus.ACTIVE, ContentStatus.ARCHIVED, ARCHIVED));
    }

    public IdempotencyExecutionResult restore(Transition command) {
        ContentLocator locator = requireOwnerVisible(command.actor(), command.contentId());
        return idempotency.execute(idempotency(command.actor(), "restoreContent",
                command.idempotencyKey(), command.requestHash()), () -> transition(
                command, locator.projectId(), ContentStatus.ARCHIVED, ContentStatus.ACTIVE, RESTORED));
    }

    private StoredCommandResult transition(Transition command, UUID projectId,
            ContentStatus expectedStatus, ContentStatus nextStatus, String eventType) {
        ProjectFactWriteSnapshot project = writable(command.actor(), projectId);
        Content before = locked(project, command.contentId());
        requireVersion(before, command.expectedVersion());
        requireStatus(before, expectedStatus);
        Content candidate = nextStatus == ContentStatus.ARCHIVED
                ? before.archive(command.actor().userId(), clock.instant())
                : before.restore(command.actor().userId(), clock.instant());
        Content after = contents.update(candidate, command.expectedVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        append(eventType, after, command.actor(), List.of("status"));
        return stored(200, view(after, template(project.templateKey(), project.templateVersion())));
    }

    private ContentLocator requireOwnerVisible(CurrentActor actor, UUID contentId) {
        ContentLocator locator = locator(actor, contentId);
        requireOwner(visible(actor, locator.projectId()));
        return locator;
    }

    private ContentLocator locator(CurrentActor actor, UUID contentId) {
        requireActor(actor);
        return contents.findLocator(actor.companyId(), contentId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private ProjectAccessSnapshot visible(CurrentActor actor, UUID projectId) {
        requireActor(actor);
        return access.findVisible(actor, projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private ProjectFactWriteSnapshot writable(CurrentActor actor, UUID projectId) {
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(actor, projectId);
        if (project.actorAccess() != ProjectFactWriteSnapshot.ActorProjectAccess.OWNER)
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        return project;
    }

    private Content locked(ProjectFactWriteSnapshot project, UUID contentId) {
        return contents.lock(project.companyId(), project.projectId(), contentId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private ProjectTemplateSnapshot template(String key, int version) {
        return templates.findAny(key, version).orElseThrow(() ->
                ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                        "TEMPLATE_UNAVAILABLE"));
    }

    private ContentView view(Content content, ProjectTemplateSnapshot template) {
        return new ContentView(content.id(), content.projectId(), content.code(), content.name(),
                content.description(), content.workItemType().name(), content.status().name(),
                content.defaultViewType().name(), configs.read(content.viewConfigJson(), template.statuses()),
                content.appliedTemplateKey(), content.appliedTemplateVersion(),
                content.appliedBlueprintCode(), content.rowVersion(), StrongEtag.format(content.rowVersion()),
                content.createdAt(), content.createdByUserId(), content.updatedAt(),
                content.updatedByUserId(), content.archivedAt(), content.archivedByUserId());
    }

    private static List<WorkflowStatusOption> statusOptions(ProjectTemplateSnapshot template) {
        return template.statuses().stream().sorted(java.util.Comparator.comparingInt(
                        ProjectTemplateSnapshot.WorkflowStatus::sortOrder))
                .map(value -> new WorkflowStatusOption(value.statusCode(), value.displayName(),
                        value.statusCategory(), value.sortOrder(), value.initial(), value.terminal())).toList();
    }

    private void append(String eventType, Content content, CurrentActor actor, List<String> changedFields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contentId", content.id()); payload.put("projectId", content.projectId());
        payload.put("code", content.code()); payload.put("name", content.name());
        payload.put("workItemType", content.workItemType().name()); payload.put("status", content.status().name());
        payload.put("defaultViewType", content.defaultViewType().name());
        payload.put("blueprintCode", content.appliedBlueprintCode());
        payload.put("rowVersion", content.rowVersion()); payload.put("changedFields", changedFields);
        events.append(new EventDraft(eventType, 1, "Content", content.id(), content.rowVersion(),
                content.companyId(), EventActor.user(actor.userId()), objectMapper.valueToTree(payload)));
    }

    private StoredCommandResult stored(int status, ContentView view) {
        try { return new StoredCommandResult(status, objectMapper.writeValueAsString(view), view.id(), view.etag()); }
        catch (JacksonException exception) { throw new IllegalStateException("content response serialization failed", exception); }
    }

    private static List<String> changed(Content before, Content after,
            String canonicalBefore, String canonicalAfter) {
        List<String> fields = new ArrayList<>();
        if (!before.name().equals(after.name())) fields.add("name");
        if (!java.util.Objects.equals(before.description(), after.description())) fields.add("description");
        if (before.defaultViewType() != after.defaultViewType()) fields.add("defaultViewType");
        if (!canonicalBefore.equals(canonicalAfter)) fields.add("viewConfig");
        return List.copyOf(fields);
    }

    private static void requireOwner(ProjectAccessSnapshot project) {
        if (project.actorAccess() != ProjectAccessSnapshot.ActorProjectAccess.OWNER)
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        if (project.lifecycle() == ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED)
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION, "PROJECT_ARCHIVED");
    }

    private static void requireVersion(Content content, long expected) {
        if (content.rowVersion() != expected) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
    }

    private static void requireStatus(Content content, ContentStatus expected) {
        if (content.status() != expected) throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
    }

    private static void requireActor(CurrentActor actor) {
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static ApplicationException validation(String field, String code, String message) {
        return ApplicationException.validation(new FieldViolation(field, code, message));
    }

    private static ContentViewType viewType(String value) {
        try { return ContentViewType.valueOf(value); }
        catch (IllegalArgumentException exception) {
            throw validation("defaultViewType", "INVALID_VALUE", "默认视图类型无效");
        }
    }

    private static IdempotencyCommand idempotency(CurrentActor actor, String route, UUID key,
            com.yumpoo.platform.foundation.application.idempotency.RequestHash hash) {
        return new IdempotencyCommand(new IdempotencyScope(actor.userId(), "POST", route, key), hash);
    }
}
