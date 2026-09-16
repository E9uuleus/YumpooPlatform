package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.application.WorkItemStatisticsRepository;
import com.yumpoo.platform.workitem.application.WorkItemStatisticsService;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

@Component
public class WorkItemStatisticsAdapter implements WorkItemStatisticsQuery {
    private final WorkItemStatisticsService service;
    public WorkItemStatisticsAdapter(WorkItemStatisticsService service) { this.service = service; }
    public Snapshot aggregate(CurrentActor actor, List<UUID> projects, Filters filters) {
        var result = service.aggregate(actor, projects, filter(filters));
        return new Snapshot(result.buckets().stream().map(WorkItemStatisticsAdapter::bucket).toList(),
                result.options().stream().map(WorkItemStatisticsAdapter::bucket).toList(), result.asOf());
    }
    public Page items(CurrentActor actor, List<UUID> projects, Filters filters, int offset, int limit) {
        var result = service.items(actor, projects, filter(filters), offset, limit);
        return new Page(result.items().stream().map(row -> {
            var i = row.item();
            return new Item(i.id(), i.projectId(), i.itemNo(), i.title(), i.assigneeUserId(), row.assigneeName(),
                    i.statusCode(), i.statusName(), i.statusCategory(), i.colorToken(), i.durationMs(), i.updatedAt());
        }).toList(), result.totalElements(), result.asOf());
    }
    public void validate(Filters filters) { service.validate(filter(filters)); }
    private static WorkItemStatisticsService.Request filter(Filters value) {
        Filters f = value == null ? Filters.empty() : value;
        return new WorkItemStatisticsService.Request(f.projectIds(), f.assignees(), f.statuses(), f.priorities(), f.contentIds(),
                f.categories(), f.dueFrom(), f.dueTo(), f.includeArchived(), f.query(), f.hasTime());
    }
    private static Bucket bucket(WorkItemStatisticsRepository.Bucket b) {
        return new Bucket(b.kind(), b.key(), b.projectId(), b.userId(), b.label(), b.code(), b.category(),
                b.colorToken(), b.count(), b.inProgress(), b.done(), b.durationMs());
    }
}
