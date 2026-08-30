package com.yumpoo.platform.audit.api;

import com.yumpoo.platform.audit.application.ActivityQueryCriteria;
import com.yumpoo.platform.audit.application.ActivityResultPage;
import com.yumpoo.platform.audit.application.ActivityService;
import com.yumpoo.platform.audit.application.ActivityStoredEvent;
import com.yumpoo.platform.audit.application.ActivitySummaryRenderer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class ActivityQueryAdapter implements ActivityQueryPort {
    private final ActivityService service;
    private final ActivitySummaryRenderer renderer;

    public ActivityQueryAdapter(ActivityService service, ActivitySummaryRenderer renderer) {
        this.service = service;
        this.renderer = renderer;
    }

    @Override
    public ActivityPage findProject(UUID companyId, UUID projectId, ActivityQuery query) {
        return page(service.findProject(companyId, projectId, criteria(query)));
    }

    @Override
    public ActivityPage findWorkItem(UUID companyId, UUID projectId, UUID workItemId,
            ActivityQuery query) {
        return page(service.findWorkItem(companyId, projectId, workItemId, criteria(query)));
    }

    private ActivityPage page(ActivityResultPage result) {
        return new ActivityPage(result.items().stream().map(this::item).toList(),
                result.nextCursor(), result.historyStartedAt());
    }

    private ActivityItemView item(ActivityStoredEvent event) {
        List<UUID> related = new ArrayList<>(2);
        if (event.primaryWorkItemId() != null) related.add(event.primaryWorkItemId());
        if (event.secondaryWorkItemId() != null) related.add(event.secondaryWorkItemId());
        return new ActivityItemView(event.id(), ActivityAudienceType.valueOf(event.audienceType()),
                event.eventType(), event.entityType(), event.entityId(), event.entityRef(), related,
                new ActivityActorView(event.actorType(), event.actorUserId(),
                        event.actorDisplayName()), event.occurredAt(), event.templateCode(),
                renderer.render(event.templateCode(), event.safeParameters()),
                event.safeParameters(), event.requestId());
    }

    private static ActivityQueryCriteria criteria(ActivityQuery query) {
        ActivityQuery source = query == null
                ? new ActivityQuery(null, null, null, null, null, null) : query;
        return new ActivityQueryCriteria(source.cursor(), source.size(), normalized(source.eventTypes()),
                normalized(source.entityTypes()), source.occurredFrom(), source.occurredTo());
    }

    private static Set<String> normalized(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.stream().map(String::strip).filter(value -> !value.isEmpty())
                .sorted(Comparator.naturalOrder()).forEach(result::add);
        return Set.copyOf(result);
    }
}
