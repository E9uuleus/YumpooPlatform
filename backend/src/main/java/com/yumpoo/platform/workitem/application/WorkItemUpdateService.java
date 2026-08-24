package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.catalog.api.ProjectActiveMembershipQuery;
import com.yumpoo.platform.catalog.api.ProjectFactWriteGuard;
import com.yumpoo.platform.catalog.api.ProjectFactWriteSnapshot;
import com.yumpoo.platform.foundation.application.collaboration.CollaborationHtmlSanitizer;
import com.yumpoo.platform.foundation.application.collaboration.CollaborationHtmlSanitizer.ParsedHtml;
import com.yumpoo.platform.foundation.application.collaboration.CollaborationHtmlSanitizer.SanitizedHtml;
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
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshot;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.ContentStatus;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemUpdate;
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

import static com.yumpoo.platform.workitem.application.WorkItemModels.WorkItemLocator;
import static com.yumpoo.platform.workitem.application.WorkItemUpdateCommands.Publish;
import static com.yumpoo.platform.workitem.application.WorkItemUpdateModels.UpdateCursor;
import static com.yumpoo.platform.workitem.application.WorkItemUpdateModels.WorkItemUpdatePage;
import static com.yumpoo.platform.workitem.application.WorkItemUpdateModels.WorkItemUpdateView;

@Service
public final class WorkItemUpdateService {
    private static final String PUBLISHED = "workitem.work_item_update_published";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final WorkItemRepository workItems;
    private final WorkItemUpdateRepository updates;
    private final ContentRepository contents;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectFactWriteGuard writeGuard;
    private final ProjectActiveMembershipQuery memberships;
    private final MinimalUserSnapshotQuery users;
    private final CollaborationHtmlSanitizer sanitizer;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final WorkItemUpdateCursorCodec cursors = new WorkItemUpdateCursorCodec();

    public WorkItemUpdateService(WorkItemRepository workItems, WorkItemUpdateRepository updates,
            ContentRepository contents, ProjectAccessSnapshotQuery access,
            ProjectFactWriteGuard writeGuard, ProjectActiveMembershipQuery memberships,
            MinimalUserSnapshotQuery users, CollaborationHtmlSanitizer sanitizer,
            IdempotentCommandExecutor idempotency, TransactionalEventPort events,
            ObjectMapper objectMapper, Clock clock) {
        this.workItems = workItems;
        this.updates = updates;
        this.contents = contents;
        this.access = access;
        this.writeGuard = writeGuard;
        this.memberships = memberships;
        this.users = users;
        this.sanitizer = sanitizer;
        this.idempotency = idempotency;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WorkItemUpdatePage list(CurrentActor actor, UUID workItemId, String cursor, Integer size) {
        WorkItemLocator locator = visibleLocator(actor, workItemId);
        ProjectAccessSnapshot project = visible(actor, locator.projectId());
        contents.find(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        workItems.find(project.companyId(), project.projectId(), locator.contentId(), workItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        int pageSize = pageSize(size);
        UpdateCursor before = cursors.decode(cursor);
        List<WorkItemUpdate> rows = updates.findOlderWindow(project.companyId(), workItemId,
                before, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) rows = new ArrayList<>(rows.subList(0, pageSize));
        rows.sort(Comparator.comparing(WorkItemUpdate::createdAt).thenComparing(WorkItemUpdate::id));
        String nextCursor = hasMore && !rows.isEmpty()
                ? cursors.encode(new UpdateCursor(rows.getFirst().createdAt(), rows.getFirst().id()))
                : null;
        return new WorkItemUpdatePage(rows.stream().map(WorkItemUpdateService::view).toList(), nextCursor);
    }

    public IdempotencyExecutionResult publish(Publish command) {
        WorkItemLocator locator = visibleLocator(command.actor(), command.workItemId());
        ProjectAccessSnapshot visible = visible(command.actor(), locator.projectId());
        requireWritable(visible.actorAccess());
        ParsedHtml parsed = parse(command.bodyHtml());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "POST", "publishWorkItemUpdate", command.idempotencyKey()),
                command.requestHash()), () -> publishLocked(command, locator, parsed));
    }

    private StoredCommandResult publishLocked(Publish command, WorkItemLocator locator,
            ParsedHtml parsed) {
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(command.actor(), locator.projectId());
        requireWritable(project.actorAccess());
        Content content = contents.lockForShare(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (content.status() != ContentStatus.ACTIVE) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "CONTENT_ARCHIVED");
        }
        WorkItem workItem = workItems.lock(project.companyId(), project.projectId(),
                        locator.contentId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));

        Set<UUID> activeIds = memberships.findActiveMemberIds(project.companyId(), project.projectId(),
                parsed.mentionedUserIds());
        if (activeIds.size() != parsed.mentionedUserIds().size()) {
            throw validation("bodyHtml", "MENTION_NOT_ACTIVE_PROJECT_MEMBER",
                    "提及目标必须是当前 Project 的 ACTIVE 成员");
        }
        Map<UUID, MinimalUserSnapshot> mentionedUsers = users.findByUserIds(project.companyId(), activeIds);
        if (mentionedUsers.size() != activeIds.size()) {
            throw validation("bodyHtml", "MENTION_IDENTITY_UNAVAILABLE", "提及目标身份不可用");
        }
        LinkedHashMap<UUID, String> names = new LinkedHashMap<>();
        parsed.mentionedUserIds().forEach(id -> names.put(id, mentionedUsers.get(id).displayName()));
        SanitizedHtml body = canonicalize(parsed, names);
        MinimalUserSnapshot author = users.findByUserId(project.companyId(), command.actor().userId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED));
        WorkItemUpdate update = WorkItemUpdate.published(UUID.randomUUID(), project.companyId(),
                project.projectId(), content.id(), workItem.id(), command.actor().userId(),
                author.displayName(), body.bodyHtml(), body.bodyText(), clock.instant());
        if (!updates.insert(update, names)) throw new IllegalStateException("work item update insert failed");
        appendPublished(update, workItem, body.mentionedUserIds(), command.actor());
        return stored(view(update));
    }

    private void appendPublished(WorkItemUpdate update, WorkItem item, List<UUID> mentions,
            CurrentActor actor) {
        List<UUID> sortedMentions = mentions.stream().sorted().toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("updateId", update.id());
        payload.put("workItemId", update.workItemId());
        payload.put("projectId", update.projectId());
        payload.put("contentId", update.contentId());
        payload.put("itemNo", item.itemNo());
        payload.put("title", item.title());
        payload.put("authorUserId", update.authorUserId());
        payload.put("mentionedUserIds", sortedMentions);
        payload.put("rowVersion", update.rowVersion());
        events.append(new EventDraft(PUBLISHED, 1, "WorkItemUpdate", update.id(),
                update.rowVersion(), update.companyId(), EventActor.user(actor.userId()),
                objectMapper.valueToTree(payload)));
    }

    private WorkItemLocator visibleLocator(CurrentActor actor, UUID workItemId) {
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
        return workItems.findLocator(actor.companyId(), workItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private ProjectAccessSnapshot visible(CurrentActor actor, UUID projectId) {
        return access.findVisible(actor, projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private ParsedHtml parse(String html) {
        try {
            return sanitizer.parse(html);
        } catch (IllegalArgumentException exception) {
            throw validation("bodyHtml", "INVALID_COLLABORATION_HTML", "讨论正文格式无效");
        }
    }

    private SanitizedHtml canonicalize(ParsedHtml parsed, Map<UUID, String> names) {
        try {
            return sanitizer.canonicalize(parsed, names);
        } catch (IllegalArgumentException exception) {
            throw validation("bodyHtml", "INVALID_COLLABORATION_HTML", "讨论正文格式或长度无效");
        }
    }

    private StoredCommandResult stored(WorkItemUpdateView view) {
        try {
            return new StoredCommandResult(201, objectMapper.writeValueAsString(view),
                    view.id(), view.etag());
        } catch (JacksonException exception) {
            throw new IllegalStateException("work item update response serialization failed", exception);
        }
    }

    private static WorkItemUpdateView view(WorkItemUpdate update) {
        return new WorkItemUpdateView(update.id(), update.projectId(), update.contentId(),
                update.workItemId(), update.authorUserId(), update.authorDisplayName(),
                update.bodyHtml(), update.bodyText(), update.status().name(), update.editDeadlineAt(),
                update.rowVersion(), StrongEtag.format(update.rowVersion()), update.createdAt(),
                update.editedAt(), update.editedByUserId(), update.deletedAt(),
                update.deletedByUserId(), update.deleteReason());
    }

    private static int pageSize(Integer value) {
        if (value == null) return DEFAULT_PAGE_SIZE;
        if (value < 1 || value > MAX_PAGE_SIZE) {
            throw validation("size", "OUT_OF_RANGE", "分页大小必须在 1 到 100 之间");
        }
        return value;
    }

    private static void requireWritable(ProjectAccessSnapshot.ActorProjectAccess actorAccess) {
        if (actorAccess == ProjectAccessSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
    }

    private static void requireWritable(ProjectFactWriteSnapshot.ActorProjectAccess actorAccess) {
        if (actorAccess == ProjectFactWriteSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
    }

    private static ApplicationException validation(String field, String code, String message) {
        return ApplicationException.validation(new FieldViolation(field, code, message));
    }
}
