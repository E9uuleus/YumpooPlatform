package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.application.content.ContentModels;
import com.yumpoo.platform.workitem.application.content.ContentRepository;
import com.yumpoo.platform.workitem.application.label.WorkItemLabelModels;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshot;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.WorkItem;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

import static com.yumpoo.platform.workitem.application.WorkItemModels.*;
import static com.yumpoo.platform.workitem.application.WorkItemEvents.dueTimeText;
import static com.yumpoo.platform.workitem.application.WorkItemEvents.priorityName;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requireActor;

@Component
public class WorkItemViews {
    private final ContentRepository contents;
    private final ProjectAccessSnapshotQuery access;
    private final MinimalUserSnapshotQuery users;
    private final ObjectMapper objectMapper;

    public WorkItemViews(ContentRepository contents, ProjectAccessSnapshotQuery access, MinimalUserSnapshotQuery users,
            ObjectMapper objectMapper) {
        this.contents = contents;
        this.access = access;
        this.users = users;
        this.objectMapper = objectMapper;
    }

    VisibleContent visibleContent(CurrentActor actor, UUID contentId) {
        requireActor(actor);
        ContentModels.ContentLocator locator = contents.findLocator(actor.companyId(), contentId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = visible(actor, locator.projectId());
        Content content = contents.find(project.companyId(), project.projectId(), contentId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        return new VisibleContent(project, content);
    }

    ProjectAccessSnapshot visible(CurrentActor actor, UUID projectId) {
        return access.findVisible(actor, projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    Map<UUID, MinimalUserSnapshot> people(UUID companyId, List<WorkItem> rows) {
        Set<UUID> userIds = new LinkedHashSet<>();
        for (WorkItem item : rows) {
            userIds.add(item.reporterUserId());
            userIds.add(item.updatedByUserId());
            userIds.addAll(item.assigneeUserIds());
        }
        return users.findByUserIds(companyId, userIds);
    }

    static String updatedByDisplayName(WorkItem item, Map<UUID, MinimalUserSnapshot> people) {
        var person = people.get(item.updatedByUserId());
        return person == null ? "历史成员" : person.displayName();
    }

    WorkItemDetail detail(WorkItem item,
            Map<UUID, MinimalUserSnapshot> people, boolean canEditFields,
            List<WorkItemLabelModels.StatusLabel> statusLabels) {
        Content content = contents.find(item.companyId(), item.projectId(), item.contentId()).orElse(null);
        return new WorkItemDetail(item.id(), item.projectId(), item.contentId(), item.itemNo(),
                content == null ? "未知类别" : content.name(),
                content == null ? "GRAY" : content.colorToken(), item.title(),
                item.statusCode(), item.statusCategory().name(),
                priorityName(item), item.assigneeUserId(), assigneeDisplayName(item, people), assignees(item, people),
                item.reporterUserId(), displayName(people.get(item.reporterUserId())),
                item.description(), item.notes(), item.timelineStartDate(), item.timelineEndDate(),
                item.dueDate(), dueTimeText(item), item.completedAt(), item.rowVersion(), StrongEtag.format(item.rowVersion()),
                new WorkItemCapabilities(canEditFields && !item.deleted() && !item.archived(),
                        canEditFields && !item.deleted() && !item.archived(), canEditFields && !item.deleted() && !item.archived(),
                        canEditFields && !item.deleted() && !item.archived(), canEditFields && !item.deleted(),
                        canEditFields && item.deleted(),
                        availableTransitions(item, canEditFields && !item.deleted() && !item.archived(), statusLabels)),
                item.createdAt(), item.updatedAt(), item.deleted(), item.deletedAt(),
                item.deletedByUserId(), item.deleteReason(), item.updatedByUserId(), updatedByDisplayName(item, people), item.archived());
    }

    static List<WorkItemTransitionOption> availableTransitions(WorkItem item,
            boolean canTransition, List<WorkItemLabelModels.StatusLabel> statusLabels) {
        if (!canTransition) return List.of();
        return statusLabels.stream().filter(WorkItemLabelModels.StatusLabel::active)
                .filter(status -> !status.code().equals(item.statusCode()))
                .sorted(Comparator.comparingInt(WorkItemLabelModels.StatusLabel::sortOrder))
                .map(status -> new WorkItemTransitionOption(status.code(), status.displayName(),
                        status.statusCategory(), false))
                .toList();
    }

    static WorkItemLabelModels.StatusLabel status(
            List<WorkItemLabelModels.StatusLabel> statuses, String statusCode) {
        return statuses.stream().filter(value -> value.code().equals(statusCode))
                .findFirst().orElseThrow(() -> ApplicationException.withReason(
                        StandardErrorCode.INVALID_STATE_TRANSITION,
                        "WORK_ITEM_TARGET_STATUS_MISSING"));
    }

    static WorkItemLabelModels.StatusLabel selectableStatus(
            List<WorkItemLabelModels.StatusLabel> statuses, String statusCode) {
        WorkItemLabelModels.StatusLabel status = status(statuses, statusCode);
        if (!status.active()) throw ApplicationException.withReason(
                StandardErrorCode.INVALID_STATE_TRANSITION, "WORK_ITEM_TARGET_STATUS_INACTIVE");
        return status;
    }

    static String assigneeDisplayName(WorkItem item,
            Map<UUID, MinimalUserSnapshot> people) {
        return item.assigneeUserId() == null ? null : displayName(people.get(item.assigneeUserId()));
    }

    static String displayName(MinimalUserSnapshot person) {
        return person == null ? "未知成员" : person.displayName();
    }

    StoredCommandResult stored(int status, WorkItemDetail view) {
        try {
            return new StoredCommandResult(status, objectMapper.writeValueAsString(view),
                    view.id(), view.etag());
        } catch (JacksonException exception) {
            throw new IllegalStateException("work item response serialization failed", exception);
        }
    }

    static List<WorkItemAssignee> assignees(WorkItem item, Map<UUID, MinimalUserSnapshot> people) {
        return item.assigneeUserIds().stream().map(id -> new WorkItemAssignee(id,
                people.containsKey(id) ? people.get(id).displayName() : "历史成员")).toList();
    }

    record VisibleContent(ProjectAccessSnapshot project, Content content) {}
}
