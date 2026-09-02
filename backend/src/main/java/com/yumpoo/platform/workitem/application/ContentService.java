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
import com.yumpoo.platform.workitem.domain.Content;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.ContentCommands.Create;
import static com.yumpoo.platform.workitem.application.ContentCommands.Delete;
import static com.yumpoo.platform.workitem.application.ContentCommands.Update;
import static com.yumpoo.platform.workitem.application.ContentModels.ContentView;
import static com.yumpoo.platform.workitem.application.ContentModels.ProjectContentCatalog;

@Service
public class ContentService {
    private static final String CREATED = "workitem.content_created";
    private static final String UPDATED = "workitem.content_updated";
    private static final String DELETED = "workitem.content_deleted";
    private static final Set<String> COLORS = Set.of(
            "BRIGHT_GREEN", "SALADISH", "EGG_YOLK", "DARK_ORANGE", "PEACH", "SUNSET",
            "DARK_RED", "SOFIA_PINK", "LIPSTICK", "BUBBLE", "DARK_PURPLE", "BERRY",
            "DARK_INDIGO", "INDIGO", "NAVY", "BRIGHT_BLUE", "AQUAMARINE", "CHILI_BLUE",
            "RIVER", "WINTER", "AMERICAN_GRAY", "BLACKISH", "BROWN", "ORCHID", "TAN",
            "SKY", "COFFEE", "ROYAL", "TEAL", "LAVENDER", "STEEL", "LILAC", "PECAN",
            "GREEN", "BLUE", "PURPLE", "MAGENTA", "RED", "ORANGE", "AMBER", "LIME",
            "CYAN", "GRAY");

    private final ContentRepository contents;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectFactWriteGuard writeGuard;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ContentService(ContentRepository contents, ProjectAccessSnapshotQuery access,
            ProjectFactWriteGuard writeGuard, IdempotentCommandExecutor idempotency,
            TransactionalEventPort events, ObjectMapper objectMapper, Clock clock) {
        this.contents = contents;
        this.access = access;
        this.writeGuard = writeGuard;
        this.idempotency = idempotency;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProjectContentCatalog catalog(CurrentActor actor, UUID projectId) {
        ProjectAccessSnapshot project = visible(actor, projectId);
        long version = contents.catalogVersion(project.companyId(), project.projectId());
        return catalog(project, version);
    }

    @Transactional(readOnly = true)
    public ContentView find(CurrentActor actor, UUID projectId, UUID contentId) {
        ProjectAccessSnapshot project = visible(actor, projectId);
        return view(contents.find(project.companyId(), projectId, contentId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND)));
    }

    public IdempotencyExecutionResult create(Create command) {
        requireOwner(visible(command.actor(), command.projectId()));
        return idempotency.execute(idempotency(command.actor(), "createContent",
                command.idempotencyKey(), command.requestHash()), () -> createStored(command));
    }

    @Transactional
    protected StoredCommandResult createStored(Create command) {
        ProjectFactWriteSnapshot project = writable(command.actor(), command.projectId());
        long version = contents.lockCatalogVersion(project.companyId(), project.projectId());
        Content content = Content.create(UUID.randomUUID(), project.companyId(), project.projectId(),
                generatedCode(), name(command.name()), color(command.colorToken()),
                contents.nextSortOrder(project.companyId(), project.projectId()),
                command.actor().userId(), clock.instant());
        if (!contents.insert(content)) {
            throw validation("name", "DUPLICATE", "工作项类别创建冲突，请重试");
        }
        long nextVersion = bump(project, version);
        append(CREATED, content, command.actor(), List.of());
        return stored(201, view(content), nextVersion);
    }

    @Transactional
    public ContentView update(Update command) {
        ProjectFactWriteSnapshot project = writable(command.actor(), command.projectId());
        long catalogVersion = contents.lockCatalogVersion(project.companyId(), project.projectId());
        requireCatalogVersion(catalogVersion, command.expectedCatalogVersion());
        Content before = contents.lock(project.companyId(), project.projectId(), command.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (command.name() == null && command.colorToken() == null
                && command.active() == null && command.sortOrder() == null) {
            throw validation("body", "EMPTY_UPDATE", "至少需要修改一个类别字段");
        }
        boolean nextActive = command.active() == null ? before.active() : command.active();
        if (before.active() && !nextActive
                && contents.countActive(project.companyId(), project.projectId(), before.id()) == 0) {
            throw validation("active", "LAST_ACTIVE_CONTENT", "项目至少需要一个启用的工作项类别");
        }

        List<Content> ordered = new ArrayList<>(contents.findAll(project.companyId(), project.projectId()));
        ordered.removeIf(value -> value.id().equals(before.id()));
        int requested = command.sortOrder() == null ? before.sortOrder() : command.sortOrder();
        int insertion = 0;
        while (insertion < ordered.size() && ordered.get(insertion).sortOrder() < requested) insertion++;
        Content candidate = before.update(
                command.name() == null ? before.name() : name(command.name()),
                command.colorToken() == null ? before.colorToken() : color(command.colorToken()),
                requested, nextActive, command.actor().userId(), clock.instant());
        ordered.add(insertion, candidate);
        List<Content> normalized = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            Content value = ordered.get(index);
            int sortOrder = (index + 1) * 10;
            normalized.add(value.id().equals(before.id())
                    ? candidate.update(candidate.name(), candidate.colorToken(), sortOrder,
                            candidate.active(), command.actor().userId(), clock.instant())
                    : value.update(value.name(), value.colorToken(), sortOrder, value.active(),
                            value.updatedByUserId(), value.updatedAt()));
        }
        contents.replaceOrder(normalized);
        Content finalCandidate = normalized.stream().filter(value -> value.id().equals(before.id()))
                .findFirst().orElseThrow();
        Content after = contents.update(finalCandidate, before.rowVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        bump(project, catalogVersion);
        append(UPDATED, after, command.actor(), changed(before, after));
        return view(after);
    }

    @Transactional
    public void delete(Delete command) {
        ProjectFactWriteSnapshot project = writable(command.actor(), command.projectId());
        long catalogVersion = contents.lockCatalogVersion(project.companyId(), project.projectId());
        requireCatalogVersion(catalogVersion, command.expectedCatalogVersion());
        Content before = contents.lock(project.companyId(), project.projectId(), command.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (before.protectedContent()) {
            throw validation("contentId", "PROTECTED_CONTENT", "默认工作项类别不可删除");
        }
        if (before.everUsed()) {
            throw validation("contentId", "CONTENT_IN_USE", "使用过的工作项类别只能停用");
        }
        if (before.active() && contents.countActive(project.companyId(), project.projectId(), before.id()) == 0) {
            throw validation("contentId", "LAST_ACTIVE_CONTENT", "项目至少需要一个启用的工作项类别");
        }
        Content after;
        try {
            after = before.delete(command.actor().userId(), clock.instant());
        } catch (IllegalStateException exception) {
            throw validation("contentId", "CONTENT_NOT_DELETABLE", exception.getMessage());
        }
        contents.update(after, before.rowVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        bump(project, catalogVersion);
        append(DELETED, after, command.actor(), List.of("deletedAt"));
    }

    private ProjectContentCatalog catalog(ProjectAccessSnapshot project, long version) {
        boolean canManage = project.lifecycle() != ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED
                && project.actorAccess() == ProjectAccessSnapshot.ActorProjectAccess.OWNER;
        return new ProjectContentCatalog(contents.findAll(project.companyId(), project.projectId())
                .stream().sorted(Comparator.comparingInt(Content::sortOrder).thenComparing(Content::id))
                .map(ContentService::view).toList(), version, StrongEtag.format(version), canManage);
    }

    private long bump(ProjectFactWriteSnapshot project, long expected) {
        if (!contents.bumpCatalogVersion(project.companyId(), project.projectId(), expected, clock.instant())) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
        return expected + 1;
    }

    private ProjectAccessSnapshot visible(CurrentActor actor, UUID projectId) {
        requireActor(actor);
        return access.findVisible(actor, projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private ProjectFactWriteSnapshot writable(CurrentActor actor, UUID projectId) {
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(actor, projectId);
        if (project.actorAccess() != ProjectFactWriteSnapshot.ActorProjectAccess.OWNER) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        return project;
    }

    private static ContentView view(Content content) {
        return new ContentView(content.id(), content.projectId(), content.code(), content.name(),
                content.colorToken(), content.sortOrder(), content.active(),
                content.protectedContent(), content.everUsed(), content.rowVersion(),
                content.createdAt(), content.createdByUserId(), content.updatedAt(),
                content.updatedByUserId());
    }

    private void append(String eventType, Content content, CurrentActor actor, List<String> changedFields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contentId", content.id());
        payload.put("projectId", content.projectId());
        payload.put("code", content.code());
        payload.put("name", content.name());
        payload.put("colorToken", content.colorToken());
        payload.put("sortOrder", content.sortOrder());
        payload.put("active", content.active());
        payload.put("protectedContent", content.protectedContent());
        payload.put("inUse", content.everUsed());
        payload.put("rowVersion", content.rowVersion());
        payload.put("changedFields", changedFields);
        events.append(new EventDraft(eventType, 2, "Content", content.id(), content.rowVersion(),
                content.companyId(), EventActor.user(actor.userId()), objectMapper.valueToTree(payload)));
    }

    private StoredCommandResult stored(int status, ContentView view, long catalogVersion) {
        try {
            return new StoredCommandResult(status, objectMapper.writeValueAsString(view),
                    view.id(), StrongEtag.format(catalogVersion));
        } catch (JacksonException exception) {
            throw new IllegalStateException("content response serialization failed", exception);
        }
    }

    private static List<String> changed(Content before, Content after) {
        List<String> fields = new ArrayList<>();
        if (!before.name().equals(after.name())) fields.add("name");
        if (!before.colorToken().equals(after.colorToken())) fields.add("colorToken");
        if (before.sortOrder() != after.sortOrder()) fields.add("sortOrder");
        if (before.active() != after.active()) fields.add("active");
        return List.copyOf(fields);
    }

    private static String generatedCode() {
        return "CAT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 28).toUpperCase();
    }

    private static String name(String value) {
        if (value == null || value.strip().isEmpty() || value.strip().length() > 80) {
            throw validation("name", "INVALID_NAME", "工作项类别名称长度必须为 1 到 80");
        }
        return value.strip();
    }

    private static String color(String value) {
        if (!COLORS.contains(value)) {
            throw validation("colorToken", "INVALID_COLOR", "类别颜色不在允许的色板中");
        }
        return value;
    }

    private static void requireOwner(ProjectAccessSnapshot project) {
        if (project.actorAccess() != ProjectAccessSnapshot.ActorProjectAccess.OWNER) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        if (project.lifecycle() == ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION, "PROJECT_ARCHIVED");
        }
    }

    private static void requireCatalogVersion(long actual, long expected) {
        if (actual != expected) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
    }

    private static void requireActor(CurrentActor actor) {
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static ApplicationException validation(String field, String code, String message) {
        return ApplicationException.validation(new FieldViolation(field, code, message));
    }

    private static IdempotencyCommand idempotency(CurrentActor actor, String route, UUID key,
            com.yumpoo.platform.foundation.application.idempotency.RequestHash hash) {
        return new IdempotencyCommand(new IdempotencyScope(actor.userId(), "POST", route, key), hash);
    }
}
