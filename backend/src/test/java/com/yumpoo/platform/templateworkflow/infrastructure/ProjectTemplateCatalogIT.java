package com.yumpoo.platform.templateworkflow.infrastructure;

import tools.jackson.databind.ObjectMapper;
import com.yumpoo.platform.administration.application.ProjectTemplateGovernanceCommand;
import com.yumpoo.platform.administration.application.ProjectTemplateGovernanceService;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateSnapshot;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateVersionCommand;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateVersionCommandPort;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateVersionQuery;
import com.yumpoo.platform.templateworkflow.api.PublishedProjectTemplateQuery;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProjectTemplateCatalogIT {

    private static final UUID ACTOR_ID = UUID.fromString("16000000-0000-4000-8000-000000000001");

    @Autowired
    private PublishedProjectTemplateQuery publishedQuery;
    @Autowired
    private ProjectTemplateVersionQuery versionQuery;
    @Autowired
    private ProjectTemplateVersionCommandPort commandPort;
    @Autowired
    private ProjectTemplateGovernanceService governanceService;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void migrationSeedsExactCatalogAndRepositoryReturnsStableCompleteSnapshots() {
        assertThat(count("project_template_definition")).isEqualTo(4);
        assertThat(count("project_template_content_blueprint")).isEqualTo(12);
        assertThat(count("workflow_status_definition")).isEqualTo(23);
        assertThat(count("workflow_transition_definition")).isEqualTo(29);

        assertThat(publishedQuery.findAllPublished())
                .extracting(ProjectTemplateSnapshot::versionCode)
                .containsExactly("RND_V1", "PRE_SALES_V1", "IMPLEMENTATION_V1", "HYPERCARE_V1");

        ProjectTemplateSnapshot rnd = publishedQuery.findPublished("RND", 1).orElseThrow();
        assertThat(rnd.lifecycleStatus()).isEqualTo("PUBLISHED");
        assertThat(rnd.rowVersion()).isEqualTo(1);
        assertThat(rnd.contentBlueprints()).extracting(ProjectTemplateSnapshot.ContentBlueprint::contentCode)
                .containsExactly("REQUIREMENTS", "TASKS", "DEFECTS");
        assertThat(rnd.statuses()).hasSize(6);
        assertThat(rnd.transitions()).hasSize(8);
        assertThat(rnd.statuses()).filteredOn(ProjectTemplateSnapshot.WorkflowStatus::initial)
                .extracting(ProjectTemplateSnapshot.WorkflowStatus::statusCode)
                .containsExactly("BACKLOG");
        assertThat(rnd.transitions()).allSatisfy(edge -> {
            assertThat(edge.requiredPermission()).isEqualTo("MEMBER");
            assertThat(edge.requiresResolution()).isFalse();
        });
    }

    @Test
    void publishedStructureAndVersionIdentityCannotBeChangedOrDeleted() {
        UUID templateId = versionQuery.findAny("RND", 1).orElseThrow().templateVersionId();

        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE yumpoo.workflow_status_definition
                           SET display_name = '篡改'
                         WHERE template_id = :templateId AND status_code = 'BACKLOG'
                        """).param("templateId", templateId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE yumpoo.project_template_definition
                           SET version_code = 'RND_V99'
                         WHERE id = :templateId
                        """).param("templateId", templateId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        DELETE FROM yumpoo.project_template_definition WHERE id = :templateId
                        """).param("templateId", templateId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void draftCanPublishThenRetireWithOptimisticLifecycleChecks() {
        insertDraftCopy(99);

        ProjectTemplateSnapshot published = commandPort.publish(new ProjectTemplateVersionCommand(
                "RND", 99, 0, ACTOR_ID, "发布验证", Instant.parse("2026-08-18T02:00:00Z")));
        assertThat(published.lifecycleStatus()).isEqualTo("PUBLISHED");
        assertThat(published.rowVersion()).isEqualTo(1);

        assertThatThrownBy(() -> commandPort.publish(new ProjectTemplateVersionCommand(
                "RND", 99, 1, ACTOR_ID, "重复发布", Instant.parse("2026-08-18T02:01:00Z"))))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(StandardErrorCode.INVALID_STATE_TRANSITION));

        ProjectTemplateSnapshot retired = commandPort.retire(new ProjectTemplateVersionCommand(
                "RND", 99, 1, ACTOR_ID, "停止新项目选择", Instant.parse("2026-08-18T02:02:00Z")));
        assertThat(retired.lifecycleStatus()).isEqualTo("RETIRED");
        assertThat(retired.rowVersion()).isEqualTo(2);
        assertThat(publishedQuery.findPublished("RND", 99)).isEmpty();

        assertThatThrownBy(() -> commandPort.retire(new ProjectTemplateVersionCommand(
                "RND", 99, 1, ACTOR_ID, "旧版本", Instant.parse("2026-08-18T02:03:00Z"))))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(StandardErrorCode.VERSION_CONFLICT));
    }

    @Test
    @Transactional
    void governanceMutationCommitsOneIdempotencyResultAuditAndOutboxEventAndReplaysExactly() throws Exception {
        insertDraftCopy(97);
        insertActor();
        UUID idempotencyKey = UUID.randomUUID();
        CurrentActor admin = new CurrentActor(
                ACTOR_ID,
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                0,
                Set.of(PlatformRoleCode.COMPANY_ADMIN));
        ProjectTemplateGovernanceCommand command = new ProjectTemplateGovernanceCommand(
                admin, "RND", 97, 0, "发布事务验证", idempotencyKey,
                new RequestHash("b".repeat(64)), null, null);

        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("m2-01-template-governance"))) {
            IdempotencyExecutionResult first = governanceService.publish(command);
            IdempotencyExecutionResult replay = governanceService.publish(command);

            assertThat(first.replayed()).isFalse();
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.result().httpStatus()).isEqualTo(first.result().httpStatus());
            assertThat(replay.result().resourceId()).isEqualTo(first.result().resourceId());
            assertThat(replay.result().etag()).isEqualTo(first.result().etag());
            assertThat(objectMapper.readTree(replay.result().responseJson()))
                    .isEqualTo(objectMapper.readTree(first.result().responseJson()));
            assertThat(first.result().etag()).isEqualTo("\"1\"");

            assertThat(jdbcClient.sql("""
                            SELECT count(*) FROM yumpoo.idempotency_record
                             WHERE actor_user_id = :actorId AND idempotency_key = :key
                            """).param("actorId", ACTOR_ID).param("key", idempotencyKey)
                    .query(Integer.class).single()).isOne();
            assertThat(jdbcClient.sql("""
                            SELECT count(*) FROM yumpoo.outbox_event
                             WHERE aggregate_id = :templateId
                               AND event_type = 'templateworkflow.project_template_published'
                            """).param("templateId", first.result().resourceId())
                    .query(Integer.class).single()).isOne();
            assertThat(jdbcClient.sql("""
                            SELECT count(*) FROM yumpoo.security_audit_event
                             WHERE target_id = :templateId AND action = 'PROJECT_TEMPLATE_PUBLISHED'
                            """).param("templateId", first.result().resourceId().toString())
                    .query(Integer.class).single()).isOne();

            ProjectTemplateGovernanceCommand conflictingBody = new ProjectTemplateGovernanceCommand(
                    admin, "RND", 97, 0, "同键异体", idempotencyKey,
                    new RequestHash("c".repeat(64)), null, null);
            assertThatThrownBy(() -> governanceService.publish(conflictingBody))
                    .isInstanceOfSatisfying(ApplicationException.class,
                            error -> assertThat(error.errorCode())
                                    .isEqualTo(StandardErrorCode.IDEMPOTENCY_KEY_REUSED));
        }
    }

    @Test
    @Transactional
    void invalidDraftStructureIsRejectedAsValidationFailure() {
        insertDraftCopy(96);
        UUID id = versionQuery.findAny("RND", 96).orElseThrow().templateVersionId();
        jdbcClient.sql("""
                        DELETE FROM yumpoo.workflow_transition_definition
                         WHERE template_id = :templateId
                           AND from_status = 'READY' AND to_status = 'CANCELED'
                        """).param("templateId", id).update();

        assertThatThrownBy(() -> commandPort.publish(new ProjectTemplateVersionCommand(
                "RND", 96, 0, ACTOR_ID, "不完整结构", Instant.parse("2026-08-18T02:04:00Z"))))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(StandardErrorCode.VALIDATION_FAILED));
        assertThat(versionQuery.findAny("RND", 96).orElseThrow().lifecycleStatus()).isEqualTo("DRAFT");
    }

    private int count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM yumpoo." + table).query(Integer.class).single();
    }

    private void insertDraftCopy(int version) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO yumpoo.project_template_definition (
                            id, template_key, template_version, version_code, project_type,
                            display_name, lifecycle_status, row_version, created_at, updated_at
                        ) VALUES (
                            :id, 'RND', :version, :versionCode, 'PRODUCT_DEVELOPMENT',
                            '产品研发测试版本', 'DRAFT', 0,
                            TIMESTAMPTZ '2026-08-18 00:00:00Z', TIMESTAMPTZ '2026-08-18 00:00:00Z'
                        )
                        """)
                .param("id", id)
                .param("version", version)
                .param("versionCode", "RND_V" + version)
                .update();
        jdbcClient.sql("""
                        INSERT INTO yumpoo.project_template_content_blueprint
                            (template_id, content_code, display_name, work_item_type, default_view_type, sort_order)
                        SELECT :id, content_code, display_name, work_item_type, default_view_type, sort_order
                          FROM yumpoo.project_template_content_blueprint source
                         WHERE source.template_id = (
                             SELECT id FROM yumpoo.project_template_definition
                              WHERE template_key = 'RND' AND template_version = 1)
                        """).param("id", id).update();
        jdbcClient.sql("""
                        INSERT INTO yumpoo.workflow_status_definition
                            (template_id, status_code, display_name, status_category, sort_order, is_initial, is_terminal)
                        SELECT :id, status_code, display_name, status_category, sort_order, is_initial, is_terminal
                          FROM yumpoo.workflow_status_definition source
                         WHERE source.template_id = (
                             SELECT id FROM yumpoo.project_template_definition
                              WHERE template_key = 'RND' AND template_version = 1)
                        """).param("id", id).update();
        jdbcClient.sql("""
                        INSERT INTO yumpoo.workflow_transition_definition
                            (template_id, from_status, to_status, required_permission, requires_resolution)
                        SELECT :id, from_status, to_status, required_permission, requires_resolution
                          FROM yumpoo.workflow_transition_definition source
                         WHERE source.template_id = (
                             SELECT id FROM yumpoo.project_template_definition
                              WHERE template_key = 'RND' AND template_version = 1)
                        """).param("id", id).update();
    }

    private void insertActor() {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.identity_user (
                            id, company_id, employment_status, account_status,
                            display_name, directory_synced_at, authorization_version,
                            row_version, created_at, updated_at
                        ) VALUES (
                            :id, '00000000-0000-4000-8000-000000000001',
                            'ACTIVE', 'ENABLED', 'M2-01 Template Admin',
                            transaction_timestamp(), 0, 0,
                            transaction_timestamp(), transaction_timestamp()
                        )
                        """)
                .param("id", ACTOR_ID)
                .update();
    }
}
