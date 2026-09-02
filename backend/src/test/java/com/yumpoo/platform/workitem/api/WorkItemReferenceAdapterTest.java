package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.application.WorkItemReferenceService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkItemReferenceAdapterTest {
    private final WorkItemReferenceService service = mock(WorkItemReferenceService.class);
    private final WorkItemReferenceAdapter adapter = new WorkItemReferenceAdapter(service);

    @Test
    void exposesOnlyMinimalActorScopedReferenceAndSeparatesDeletedLookup() {
        UUID companyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID workItemId = UUID.randomUUID();
        CurrentActor actor = new CurrentActor(userId, companyId, 0, Set.of());
        WorkItemReferenceService.Reference reference = new WorkItemReferenceService.Reference(
                workItemId, UUID.randomUUID(), UUID.randomUUID(), "需求", "BRIGHT_BLUE",
                "REQ-12", "公开标题", "IN_PROGRESS", "IN_PROGRESS", true);
        when(service.findVisible(actor, workItemId, false)).thenReturn(Optional.empty());
        when(service.findVisible(actor, workItemId, true)).thenReturn(Optional.of(reference));

        assertThat(adapter.findVisible(actor, workItemId)).isEmpty();
        assertThat(adapter.findVisibleIncludingDeleted(actor, workItemId)).contains(
                new WorkItemReferenceSnapshot(reference.workItemId(), reference.projectId(),
                        reference.contentId(), reference.contentName(),
                        reference.contentColorToken(), reference.itemNo(),
                        reference.title(), reference.statusCode(),
                        reference.statusCategory(), true));
        verify(service).findVisible(actor, workItemId, false);
        verify(service).findVisible(actor, workItemId, true);
    }
}
