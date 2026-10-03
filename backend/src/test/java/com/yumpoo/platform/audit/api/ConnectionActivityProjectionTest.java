package com.yumpoo.platform.audit.api;

import com.yumpoo.platform.audit.application.ActivityRepository;
import com.yumpoo.platform.audit.application.ActivityStoredEvent;
import com.yumpoo.platform.audit.application.ActivitySummaryRenderer;
import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventActor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConnectionActivityProjectionTest {
    @Test
    void connectionProjectionUsesOnlyEventColumnNameAndNeverLooksUpWorkItems() {
        var repository = mock(ActivityRepository.class);
        var context = mock(ActivityProjectionContextPort.class);
        var json = new ObjectMapper();
        when(repository.acceptedFrom()).thenReturn(Instant.EPOCH);
        var payload = json.createObjectNode().put("sourceProjectId", UUID.randomUUID().toString())
                .put("targetProjectId", UUID.randomUUID().toString()).put("sourceWorkItemId", UUID.randomUUID().toString())
                .put("targetWorkItemId", UUID.randomUUID().toString()).put("columnName", "事件中的列名").put("origin", "CREATED");
        var event = new DomainEventEnvelope(UUID.randomUUID(), "workitem.connection_created", 1, Instant.now(),
                "WorkItemConnection", UUID.randomUUID(), 0, UUID.randomUUID(), EventActor.user(UUID.randomUUID()),
                "connection-test", "connection-test", null, payload);
        new ActivityProjectionService(repository, context, json).consume(event);
        var captured = ArgumentCaptor.forClass(ActivityStoredEvent.class);
        verify(repository, times(2)).append(captured.capture());
        verify(context, never()).workItem(any(), any());
        var rows = captured.getAllValues();
        assertThat(rows.get(0).safeParameters().path("columnName").asText()).isEqualTo("事件中的列名");
        assertThat(rows.get(1).safeParameters().isEmpty()).isTrue();
        var renderer = new ActivitySummaryRenderer();
        assertThat(renderer.render("CONNECT_COLUMN_CREATED", json.createObjectNode().put("name", "研发跟进")))
                .isEqualTo("添加了连接列「研发跟进」");
        assertThat(renderer.render("CONNECT_COLUMN_UPDATED", json.createObjectNode().put("name", "研发跟进")))
                .isEqualTo("修改了连接列「研发跟进」");
    }
}
