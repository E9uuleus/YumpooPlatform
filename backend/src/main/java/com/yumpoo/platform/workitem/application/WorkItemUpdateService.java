package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.catalog.api.ProjectActiveMembershipQuery;
import com.yumpoo.platform.catalog.api.ProjectFactWriteGuard;
import com.yumpoo.platform.catalog.api.ProjectFactWriteSnapshot;
import com.yumpoo.platform.catalog.api.ProjectModerationGuard;
import com.yumpoo.platform.catalog.api.ProjectModerationSnapshot;
import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
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
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshot;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemUpdate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemModels.WorkItemLocator;
import static com.yumpoo.platform.workitem.application.WorkItemUpdateCommands.Publish;
import static com.yumpoo.platform.workitem.application.WorkItemUpdateCommands.Edit;
import static com.yumpoo.platform.workitem.application.WorkItemUpdateCommands.Delete;
import static com.yumpoo.platform.workitem.application.WorkItemUpdateCommands.Pin;
import static com.yumpoo.platform.workitem.application.WorkItemUpdateModels.UpdateCursor;
import static com.yumpoo.platform.workitem.application.WorkItemUpdateModels.UpdateLocator;
import static com.yumpoo.platform.workitem.application.WorkItemUpdateModels.WorkItemUpdateCapabilities;
import static com.yumpoo.platform.workitem.application.WorkItemUpdateModels.WorkItemUpdatePage;
import static com.yumpoo.platform.workitem.application.WorkItemUpdateModels.WorkItemUpdateView;

@Service
public class WorkItemUpdateService {
    private static final String PUBLISHED = "workitem.work_item_update_published";
    private static final String EDITED = "workitem.work_item_update_edited";
    private static final String DELETED = "workitem.work_item_update_deleted";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final WorkItemRepository workItems;
    private final WorkItemUpdateRepository updates;
    private final ContentRepository contents;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectFactWriteGuard writeGuard;
    private final ProjectModerationGuard moderationGuard;
    private final ProjectActiveMembershipQuery memberships;
    private final MinimalUserSnapshotQuery users;
    private final CollaborationHtmlSanitizer sanitizer;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final SecurityAuditAppendPort audits;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final WorkItemUpdateCursorCodec cursors = new WorkItemUpdateCursorCodec();

    public WorkItemUpdateService(WorkItemRepository workItems, WorkItemUpdateRepository updates,
            ContentRepository contents, ProjectAccessSnapshotQuery access,
            ProjectFactWriteGuard writeGuard, ProjectModerationGuard moderationGuard,
            ProjectActiveMembershipQuery memberships,
            MinimalUserSnapshotQuery users, CollaborationHtmlSanitizer sanitizer,
            IdempotentCommandExecutor idempotency, TransactionalEventPort events,
            SecurityAuditAppendPort audits, ObjectMapper objectMapper, Clock clock) {
        this.workItems = workItems;
        this.updates = updates;
        this.contents = contents;
        this.access = access;
        this.writeGuard = writeGuard;
        this.moderationGuard = moderationGuard;
        this.memberships = memberships;
        this.users = users;
        this.sanitizer = sanitizer;
        this.idempotency = idempotency;
        this.events = events;
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WorkItemUpdatePage list(CurrentActor actor, UUID workItemId, String cursor, Integer size) {
        WorkItemLocator locator = visibleLocator(actor, workItemId);
        ProjectAccessSnapshot project = visible(actor, locator.projectId());
        Content content = contents.find(project.companyId(), project.projectId(), locator.contentId())
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
        boolean writable = project.lifecycle() != ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED
                && project.actorAccess() != ProjectAccessSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY;
        boolean owner = project.actorAccess() == ProjectAccessSnapshot.ActorProjectAccess.OWNER;
        return new WorkItemUpdatePage(rows.stream()
                .map(row -> view(row, actor, writable, owner)).toList(), nextCursor,
                before == null ? updates.findPinned(project.companyId(), workItemId).stream()
                    .map(row -> view(row, actor, writable, owner)).toList() : List.of());
    }

    @Transactional(readOnly = true)
    public WorkItemUpdateView find(CurrentActor actor, UUID updateId) {
        ReadContext context = visibleUpdate(actor, updateId);
        WorkItemUpdate update = updates.find(context.project().companyId(), updateId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        boolean writable = context.project().lifecycle() != ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED
                && context.project().actorAccess() != ProjectAccessSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY;
        return view(update, actor, writable,
                context.project().actorAccess() == ProjectAccessSnapshot.ActorProjectAccess.OWNER);
    }

    @Transactional
    public WorkItemUpdateView edit(Edit command) {
        UpdateLocator locator = requiredLocator(command.actor(), command.updateId());
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(command.actor(), locator.projectId());
        requireWritable(project.actorAccess());
        Content content = contents.lockForShare(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        WorkItem item = workItems.lock(project.companyId(), project.projectId(), locator.contentId(),
                        locator.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        WorkItemUpdate before = lockedUpdate(project.companyId(), locator);
        requireVersion(before, command.expectedVersion());
        if (!before.authorUserId().equals(command.actor().userId())) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }

        ParsedHtml parsed = parse(command.bodyHtml());
        Map<UUID, String> names = mentionedNames(project.companyId(), project.projectId(), parsed);
        SanitizedHtml body = canonicalize(parsed, names);
        WorkItemUpdate after;
        try {
            after = before.edit(body.bodyHtml(), body.bodyText(), command.actor().userId(), clock.instant());
        } catch (IllegalStateException exception) {
            throw invalidState(exception.getMessage());
        }
        if (after == before) {
            return view(before, command.actor(), true,
                    project.actorAccess() == ProjectFactWriteSnapshot.ActorProjectAccess.OWNER);
        }
        Map<UUID, String> previousMentions = updates.findMentionedDisplayNames(project.companyId(), before.id());
        if (!updates.update(after, names, command.expectedVersion())) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
        appendEdited(before, after, item, previousMentions.keySet(), names.keySet(), command.actor());
        return view(after, command.actor(), true,
                project.actorAccess() == ProjectFactWriteSnapshot.ActorProjectAccess.OWNER);
    }

    @Transactional
    public WorkItemUpdateView delete(Delete command) {
        UpdateLocator locator = requiredLocator(command.actor(), command.updateId());
        ProjectModerationSnapshot project = moderationGuard.lockForModeration(command.actor(), locator.projectId());
        boolean owner = project.actorAccess() == ProjectModerationSnapshot.ActorProjectAccess.OWNER;
        if (project.actorAccess() == ProjectModerationSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY
                || (!owner && project.lifecycle() == ProjectModerationSnapshot.ProjectLifecycle.ARCHIVED)) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        contents.lockForShare(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        WorkItem item = workItems.lock(project.companyId(), project.projectId(), locator.contentId(), locator.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        WorkItemUpdate before = lockedUpdate(project.companyId(), locator);
        requireVersion(before, command.expectedVersion());
        if (!owner && !before.authorUserId().equals(command.actor().userId())) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        WorkItemUpdate after = deleteOne(before, item, command.actor(),
                before.authorUserId().equals(command.actor().userId()) ? "SELF" : "ADMIN", owner);
        if (before.parentUpdateId() == null) {
            List<WorkItemUpdate> children;
            do {
                children = updates.findReplies(project.companyId(), before.id(), null, 100);
                for (WorkItemUpdate child : children) deleteOne(child, item, command.actor(), "PARENT_DELETE", owner);
            } while (!children.isEmpty());
        }
        return view(after, command.actor(), false, false);
    }

    private WorkItemUpdate deleteOne(WorkItemUpdate before, WorkItem item, CurrentActor actor,
            String mode, boolean owner) {
        WorkItemUpdate after;
        try { after = before.delete(actor.userId(), clock.instant()); }
        catch (IllegalStateException failure) { throw invalidState(failure.getMessage()); }
        Set<UUID> mentions = updates.findMentionedDisplayNames(before.companyId(), before.id()).keySet();
        if (!updates.delete(after, before.rowVersion())) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        appendDeleteAudit(before, after, actor, mode, mentions, owner);
        appendDeleted(before, after, item, mode, actor);
        return after;
    }

    public void recordDeleteFailure(CurrentActor actor, UUID updateId, RuntimeException failure) {
        String code = failure instanceof ApplicationException application
                ? application.errorCode().name() : StandardErrorCode.INTERNAL_ERROR.name();
        audits.appendIndependent(new SecurityAuditDraft(actor.companyId(),
                "work-item-update-delete-failed:" + RequestCorrelationContext.required().requestId(),
                "WORK_ITEM_UPDATE_DELETE_FAILED", SecurityAuditOutcome.FAILED,
                SecurityAuditActor.user(actor.userId(), roleNames(actor, false)), "WORK_ITEM_UPDATE",
                updateId.toString(), null, null, null, code, null, null, null, clock.instant()));
    }

    @Transactional
    public WorkItemUpdateView pin(Pin command) {
        UpdateLocator locator = requiredLocator(command.actor(), command.updateId());
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(command.actor(), locator.projectId());
        requireWritable(project.actorAccess());
        contents.lockForShare(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        WorkItem item = workItems.lock(project.companyId(), project.projectId(), locator.contentId(), locator.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        WorkItemUpdate before = lockedUpdate(project.companyId(), locator);
        requireVersion(before, command.expectedVersion());
        WorkItemUpdate after;
        try { after = before.pin(command.pinned(), command.actor().userId(), clock.instant()); }
        catch (IllegalStateException failure) { throw invalidState(failure.getMessage()); }
        if (after != before) {
            if (!updates.pin(after, command.expectedVersion())) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
            Map<String, Object> payload = updateReferences(after, item);
            payload.put("pinned", command.pinned());
            payload.put("pinnedByUserId", after.pinnedByUserId());
            payload.put("rowVersion", after.rowVersion());
            events.append(new EventDraft("workitem.work_item_update_pin_changed", 1, "WorkItemUpdate", after.id(),
                    after.rowVersion(), after.companyId(), EventActor.user(command.actor().userId()), objectMapper.valueToTree(payload)));
        }
        return view(after, command.actor(), true, project.actorAccess() == ProjectFactWriteSnapshot.ActorProjectAccess.OWNER);
    }

    @Transactional(readOnly = true)
    public WorkItemUpdatePage replies(CurrentActor actor, UUID updateId, String cursor, Integer size) {
        ReadContext context = visibleUpdate(actor, updateId);
        WorkItemUpdate parent = updates.find(context.project().companyId(), updateId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireReplyParent(parent, parent.workItemId());
        boolean writable = context.project().lifecycle() != ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED
                && context.project().actorAccess() != ProjectAccessSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY;
        return replyPage(parent, actor, writable,
                context.project().actorAccess() == ProjectAccessSnapshot.ActorProjectAccess.OWNER, cursor, pageSize(size));
    }

    private WorkItemUpdatePage replyPage(WorkItemUpdate parent, CurrentActor actor, boolean writable,
            boolean owner, String cursor, int size) {
        List<WorkItemUpdate> rows = updates.findReplies(parent.companyId(), parent.id(), cursors.decode(cursor), size + 1);
        boolean more = rows.size() > size;
        if (more) rows = rows.subList(0, size);
        String next = more ? cursors.encode(new UpdateCursor(rows.getLast().createdAt(), rows.getLast().id())) : null;
        return new WorkItemUpdatePage(rows.stream().map(row -> view(row, actor, writable, owner)).toList(), next, List.of());
    }

    private void requireReplyParent(WorkItemUpdate parent, UUID workItemId) {
        if (!parent.workItemId().equals(workItemId)) throw validation("parentUpdateId", "INVALID_PARENT", "父评论不属于当前工作项");
        if (parent.parentUpdateId() != null) throw validation("parentUpdateId", "REPLY_DEPTH_EXCEEDED", "只能回复主评论");
        if (parent.status() == com.yumpoo.platform.workitem.domain.WorkItemUpdateStatus.DELETED) throw invalidState("UPDATE_ALREADY_DELETED");
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
        WorkItem workItem = workItems.lock(project.companyId(), project.projectId(),
                        locator.contentId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));

        if (command.parentUpdateId() != null) {
            WorkItemUpdate parent = updates.lock(project.companyId(), command.parentUpdateId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            requireReplyParent(parent, command.workItemId());
        }

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
                author.displayName(), body.bodyHtml(), body.bodyText(), clock.instant(), command.parentUpdateId());
        if (!updates.insert(update, names)) throw new IllegalStateException("work item update insert failed");
        appendPublished(update, workItem, body.mentionedUserIds(), command.actor());
        return stored(view(update, command.actor(), true,
                project.actorAccess() == ProjectFactWriteSnapshot.ActorProjectAccess.OWNER));
    }

    private ReadContext visibleUpdate(CurrentActor actor, UUID updateId) {
        UpdateLocator locator = requiredLocator(actor, updateId);
        ProjectAccessSnapshot project = visible(actor, locator.projectId());
        Content content = contents.find(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        WorkItem item = workItems.find(project.companyId(), project.projectId(), locator.contentId(),
                        locator.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        return new ReadContext(locator, project, content, item);
    }

    private UpdateLocator requiredLocator(CurrentActor actor, UUID updateId) {
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
        return updates.findLocator(actor.companyId(), updateId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private WorkItemUpdate lockedUpdate(UUID companyId, UpdateLocator locator) {
        WorkItemUpdate update = updates.lock(companyId, locator.updateId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (!update.projectId().equals(locator.projectId())
                || !update.contentId().equals(locator.contentId())
                || !update.workItemId().equals(locator.workItemId())) {
            throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        }
        return update;
    }

    private Map<UUID, String> mentionedNames(UUID companyId, UUID projectId, ParsedHtml parsed) {
        Set<UUID> activeIds = memberships.findActiveMemberIds(companyId, projectId,
                parsed.mentionedUserIds());
        if (activeIds.size() != parsed.mentionedUserIds().size()) {
            throw validation("bodyHtml", "MENTION_NOT_ACTIVE_PROJECT_MEMBER",
                    "提及目标必须是当前 Project 的 ACTIVE 成员");
        }
        Map<UUID, MinimalUserSnapshot> mentionedUsers = users.findByUserIds(companyId, activeIds);
        if (mentionedUsers.size() != activeIds.size()) {
            throw validation("bodyHtml", "MENTION_IDENTITY_UNAVAILABLE", "提及目标身份不可用");
        }
        LinkedHashMap<UUID, String> names = new LinkedHashMap<>();
        parsed.mentionedUserIds().forEach(id -> names.put(id, mentionedUsers.get(id).displayName()));
        return Map.copyOf(names);
    }

    private void appendEdited(WorkItemUpdate before, WorkItemUpdate after, WorkItem item,
            Set<UUID> previousMentions, Set<UUID> currentMentions, CurrentActor actor) {
        Set<UUID> added = new HashSet<>(currentMentions);
        added.removeAll(previousMentions);
        Set<UUID> removed = new HashSet<>(previousMentions);
        removed.removeAll(currentMentions);
        Map<String, Object> payload = updateReferences(after, item);
        payload.put("authorUserId", after.authorUserId());
        payload.put("editedByUserId", actor.userId());
        payload.put("previousStatus", before.status().name());
        payload.put("status", after.status().name());
        payload.put("previousRowVersion", before.rowVersion());
        payload.put("rowVersion", after.rowVersion());
        payload.put("previousBodyTextLength", before.bodyText().length());
        payload.put("bodyTextLength", after.bodyText().length());
        payload.put("mentionedUserIds", currentMentions.stream().sorted().toList());
        payload.put("addedMentionedUserIds", added.stream().sorted().toList());
        payload.put("removedMentionedUserIds", removed.stream().sorted().toList());
        events.append(new EventDraft(EDITED, 2, "WorkItemUpdate", after.id(), after.rowVersion(),
                after.companyId(), EventActor.user(actor.userId()), objectMapper.valueToTree(payload)));
    }

    private void appendDeleted(WorkItemUpdate before, WorkItemUpdate after, WorkItem item,
            String mode, CurrentActor actor) {
        Map<String, Object> payload = updateReferences(after, item);
        payload.put("authorUserId", after.authorUserId());
        payload.put("deletedByUserId", actor.userId());
        payload.put("deletionMode", mode);

        payload.put("previousStatus", before.status().name());
        payload.put("previousRowVersion", before.rowVersion());
        payload.put("rowVersion", after.rowVersion());
        events.append(new EventDraft(DELETED, 2, "WorkItemUpdate", after.id(), after.rowVersion(),
                after.companyId(), EventActor.user(actor.userId()), objectMapper.valueToTree(payload)));
    }

    private void appendDeleteAudit(WorkItemUpdate before, WorkItemUpdate after, CurrentActor actor,
            String mode, Set<UUID> mentions, boolean projectOwner) {
        audits.append(new SecurityAuditDraft(after.companyId(),
                "work-item-update-deleted:" + after.id() + ":" + after.rowVersion(),
                "WORK_ITEM_UPDATE_DELETED",
                SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.user(actor.userId(), roleNames(actor, projectOwner)),
                "WORK_ITEM_UPDATE", after.id().toString(), mode,
                objectMapper.valueToTree(safeSummary(before, mentions)),
                objectMapper.valueToTree(safeSummary(after, mentions)), null, null,
                null, null, after.deletedAt()));
    }

    private static Map<String, Object> safeSummary(WorkItemUpdate update, Set<UUID> mentions) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("updateId", update.id());
        summary.put("workItemId", update.workItemId());
        summary.put("status", update.status().name());
        summary.put("rowVersion", update.rowVersion());
        summary.put("bodyTextLength", update.bodyText() == null ? 0 : update.bodyText().length());
        summary.put("mentionedUserIds", mentions.stream().sorted().toList());
        return summary;
    }

    private static Map<String, Object> updateReferences(WorkItemUpdate update, WorkItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("updateId", update.id());
        payload.put("parentUpdateId", update.parentUpdateId());
        payload.put("workItemId", update.workItemId());
        payload.put("projectId", update.projectId());
        payload.put("contentId", update.contentId());
        payload.put("itemNo", item.itemNo());
        payload.put("title", item.title());
        return payload;
    }

    private void appendPublished(WorkItemUpdate update, WorkItem item, List<UUID> mentions,
            CurrentActor actor) {
        List<UUID> sortedMentions = mentions.stream().sorted().toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("updateId", update.id());
        payload.put("parentUpdateId", update.parentUpdateId());
        payload.put("workItemId", update.workItemId());
        payload.put("projectId", update.projectId());
        payload.put("contentId", update.contentId());
        payload.put("itemNo", item.itemNo());
        payload.put("title", item.title());
        payload.put("authorUserId", update.authorUserId());
        payload.put("mentionedUserIds", sortedMentions);
        payload.put("rowVersion", update.rowVersion());
        events.append(new EventDraft(PUBLISHED, 2, "WorkItemUpdate", update.id(),
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

    private WorkItemUpdateView view(WorkItemUpdate update, CurrentActor actor,
            boolean parentWritable, boolean projectOwner) {
        boolean active = update.status() != com.yumpoo.platform.workitem.domain.WorkItemUpdateStatus.DELETED;
        boolean author = update.authorUserId().equals(actor.userId());
        boolean root = update.parentUpdateId() == null;
        WorkItemUpdatePage children = active && root
                ? replyPage(update, actor, parentWritable, projectOwner, null, DEFAULT_PAGE_SIZE)
                : new WorkItemUpdatePage(List.of(), null, List.of());
        return new WorkItemUpdateView(update.id(), update.projectId(), update.contentId(),
                update.workItemId(), update.authorUserId(), update.authorDisplayName(),
                update.bodyHtml(), update.bodyText(), update.status().name(),
                update.rowVersion(), StrongEtag.format(update.rowVersion()), update.createdAt(),
                update.editedAt(), update.editedByUserId(), update.deletedAt(), update.deletedByUserId(),
                update.parentUpdateId(), update.pinnedAt(), update.pinnedByUserId(),
                active && root ? updates.countReplies(update.companyId(), update.id()) : 0,
                children.items(), children.nextCursor(),
                new WorkItemUpdateCapabilities(active && parentWritable && author,
                        active && (projectOwner || (parentWritable && author)),
                        active && parentWritable && root, active && parentWritable && root));
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

    private static void requireVersion(WorkItemUpdate update, long expectedVersion) {
        if (update.rowVersion() != expectedVersion) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
    }

    private static ApplicationException invalidState(String reason) {
        return ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                reason == null || reason.isBlank() ? "WORK_ITEM_UPDATE_INVALID_STATE" : reason);
    }

    private static Set<String> roleNames(CurrentActor actor, boolean projectOwner) {
        Set<String> roles = new HashSet<>();
        actor.platformRoles().forEach(role -> roles.add(role.name()));
        if (projectOwner) roles.add("PROJECT_OWNER");
        return Set.copyOf(roles);
    }

    private static ApplicationException validation(String field, String code, String message) {
        return ApplicationException.validation(new FieldViolation(field, code, message));
    }

    private record ReadContext(UpdateLocator locator, ProjectAccessSnapshot project,
            Content content, WorkItem item) {}
}
