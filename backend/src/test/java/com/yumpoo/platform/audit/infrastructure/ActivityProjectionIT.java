package com.yumpoo.platform.audit.infrastructure;

import com.yumpoo.platform.audit.api.ActivityProjectionService;
import com.yumpoo.platform.audit.application.ActivityRepository;
import com.yumpoo.platform.audit.application.ActivityStoredEvent;
import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "yumpoo.outbox.enabled=false"
)
class ActivityProjectionIT {
    private static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("44000000-0000-4000-8000-000000000101");

    @Autowired private ActivityProjectionService projection;
    @Autowired private ActivityRepository repository;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM yumpoo.activity_event WHERE company_id = :company")
                .param("company", COMPANY).update();
    }

    @Test
    void v44PersistsGlobalCutoverAndOnlyProjectsEventsAtOrAfterIt() {
        Instant cutover = repository.acceptedFrom();

        projection.consume(projectCreated(UUID.randomUUID(), cutover.minusMillis(1), "BEFORE"));
        projection.consume(projectCreated(UUID.randomUUID(), cutover.plusMillis(1), "AFTER"));

        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.activity_projection_state WHERE projection_code='ACTIVITY_V1'")
                .query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT projection_code || ':' || scope_type FROM yumpoo.activity_event WHERE company_id=:company")
                .param("company", COMPANY).query(String.class).single())
                .isEqualTo("ACTIVITY_V1:PROJECT");
    }

    @Test
    void duplicateAndConcurrentAppendRemainSingleRowAndCursorIndexesExist() throws Exception {
        UUID eventId = UUID.randomUUID();
        ActivityStoredEvent first = stored(UUID.randomUUID(), eventId);
        ActivityStoredEvent second = stored(UUID.randomUUID(), eventId);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> { await(start); repository.append(first); });
            var right = executor.submit(() -> { await(start); repository.append(second); });
            start.countDown();
            left.get(10, TimeUnit.SECONDS);
            right.get(10, TimeUnit.SECONDS);
        }

        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.activity_event WHERE event_id=:event")
                .param("event", eventId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT indexname FROM pg_indexes WHERE schemaname='yumpoo' AND tablename='activity_event'")
                .query(String.class).list()).contains(
                        "idx_activity_event_scope_cursor",
                        "idx_activity_event_primary_work_item_cursor",
                        "idx_activity_event_secondary_work_item_cursor");
    }

    @Test
    void projectionAndScopeConstraintsRejectInvalidRows() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO yumpoo.activity_event (
                            id,event_id,projection_code,company_id,scope_type,scope_id,
                            entity_type,entity_id,event_type,actor_type,actor_system_code,
                            actor_display_name,occurred_at,template_code,safe_parameters,
                            entity_version,request_id,correlation_id
                        ) VALUES (
                            :id,:event,'PROJECT',:company,'PROJECT',:scope,
                            'PROJECT',:entity,'catalog.project_created','SYSTEM','TEST',
                            '系统',transaction_timestamp(),'PROJECT_CREATED','{}'::jsonb,
                            0,'m2-20-invalid','m2-20-invalid'
                        )
                        """).param("id", UUID.randomUUID()).param("event", UUID.randomUUID())
                .param("company", COMPANY).param("scope", PROJECT).param("entity", PROJECT)
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    private DomainEventEnvelope projectCreated(UUID eventId, Instant occurredAt, String code) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("projectId", PROJECT.toString());
        payload.put("workspaceId", UUID.randomUUID().toString());
        payload.put("code", code);
        payload.put("name", "Activity 集成测试");
        payload.put("projectType", "PRODUCT_DEVELOPMENT");
        payload.put("lifecycle", "DRAFT");
        payload.put("ownerUserId", UUID.randomUUID().toString());
        payload.put("templateKey", "RND");
        payload.put("templateVersion", 1);
        payload.put("initializedContentCount", 1);
        return new DomainEventEnvelope(eventId, "catalog.project_created", 1, occurredAt,
                "Project", PROJECT, 0, COMPANY, EventActor.system("M2_20_TEST"),
                "m2-20-cutover", "m2-20-cutover", null, payload);
    }

    private ActivityStoredEvent stored(UUID id, UUID eventId) {
        return new ActivityStoredEvent(id, eventId, "PROJECT", COMPANY, PROJECT, "PROJECT",
                PROJECT, "M2-20 Activity", "catalog.project_created", "SYSTEM", null,
                "M2_20_TEST", "系统", repository.acceptedFrom().plusSeconds(1),
                "PROJECT_CREATED", objectMapper.createObjectNode(), 0,
                "m2-20-concurrent", "m2-20-concurrent", null, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }
}
