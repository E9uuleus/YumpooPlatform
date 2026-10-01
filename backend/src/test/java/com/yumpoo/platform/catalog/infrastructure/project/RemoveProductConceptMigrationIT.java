package com.yumpoo.platform.catalog.infrastructure.project;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RemoveProductConceptMigrationIT {
    private static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final List<String> RETIRED_EVENTS = List.of(
            "catalog.product_created", "catalog.product_updated", "catalog.product_archived",
            "catalog.product_restored", "catalog.product_owner_reassigned", "catalog.product_linked_to_project",
            "catalog.project_product_link_updated", "catalog.product_unlinked_from_project");

    @Container
    private final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:17.10-alpine"))
            .withDatabaseName("up1_migration").withUsername("yumpoo_test").withPassword("yumpoo_test");
    private JdbcClient jdbc;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void createV59Facts() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = JdbcClient.create(dataSource);
        flyway("59").migrate();
        sql("""
                INSERT INTO yumpoo.identity_user (id, company_id, employment_status, account_status,
                    display_name, directory_synced_at, row_version, created_at, updated_at)
                VALUES (:user, :company, 'ACTIVE', 'ENABLED', 'UP1 Owner', now(), 0, now(), now())
                """).update();
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            sql("""
                    INSERT INTO yumpoo.project (id, company_id, workspace_id, project_code, name, project_type,
                        lifecycle, owner_user_id, template_key, template_version, row_version,
                        created_at, created_by_user_id, updated_at, updated_by_user_id)
                    SELECT :project, :company, id, 'UP1_PROJECT', 'UP1 Project', 'PRODUCT_DEVELOPMENT',
                        'DRAFT', :user, 'RND', 1, 0, now(), :user, now(), :user
                    FROM yumpoo.workspace WHERE company_id=:company AND code='MAIN'
                    """).update();
            sql("""
                    INSERT INTO yumpoo.project_membership (id, company_id, project_id, user_id, status,
                        joined_at, joined_by_user_id, row_version)
                    VALUES (:id, :company, :project, :user, 'ACTIVE', now(), :user, 0)
                    """).update();
        });
        sql("""
                INSERT INTO yumpoo.product (id, company_id, product_code, name, status, owner_user_id,
                    row_version, created_at, created_by_user_id, updated_at, updated_by_user_id)
                VALUES (:product, :company, 'UP1_PRODUCT', 'UP1 Product', 'ACTIVE', :user, 0, now(), :user, now(), :user)
                """).update();
        sql("""
                INSERT INTO yumpoo.project_product_link (id, company_id, project_id, product_id, relation_type,
                    is_primary, linked_at, linked_by_user_id, updated_at, updated_by_user_id, row_version)
                VALUES (:id, :company, :project, :product, 'DEVELOPMENT', true, now(), :user, now(), :user, 0)
                """).update();
        for (String type : RETIRED_EVENTS) insertEvent(type);
        insertEvent("catalog.project_created");
        insertEvent("identity.user_account_disabled");
        insertActivity("PRODUCT", "OTHER", "other.created");
        insertActivity("FEEDBACK", "OTHER", "other.created");
        insertActivity("PROJECT", "PRODUCT", "other.created");
        for (String type : RETIRED_EVENTS) insertActivity("PROJECT", "OTHER", type);
        insertActivity("PROJECT", "PROJECT", "catalog.project_created");
        insertIssue("PRODUCT", PRODUCT);
        insertIssue("PROJECT", PROJECT);
        insertOverride("PRODUCT_ARCHIVE_WITH_BLOCKERS", "PROJECT");
        insertOverride("PROJECT_ARCHIVE_WITH_OPEN_ITEMS", "PRODUCT");
        insertOverride("PROJECT_ARCHIVE_WITH_OPEN_ITEMS", "PROJECT");
        insertAttachment("WORK_ITEM");
        insertAttachment("WORK_ITEM_UPDATE");
        sql("""
                INSERT INTO yumpoo.security_audit_event (id, company_id, fact_key, action, outcome,
                    actor_type, actor_user_id, target_type, target_id, before_summary, after_summary,
                    request_id, correlation_id, occurred_at)
                VALUES (:id, :company, 'up1-product-audit', 'PRODUCT_CREATED', 'SUCCEEDED', 'USER', :user,
                    'PRODUCT', :product::text, '{"historical":true}', '{"keep":true}', 'up1', 'up1', now())
                """).update();
    }

    @Test
    void removesOnlyDeclaredProductFactsAndTightensConstraintsFromV59() {
        var audits = rows("security_audit_event");
        var projects = rows("project");
        var memberships = rows("project_membership");
        var templates = rows("project_template_definition");
        var attachments = rows("attachment");
        flyway("60").migrate();

        assertThat(jdbc.sql("SELECT to_regclass('yumpoo.product') IS NULL AND to_regclass('yumpoo.project_product_link') IS NULL")
                .query(Boolean.class).single()).isTrue();
        assertThat(rows("security_audit_event")).isEqualTo(audits);
        assertThat(rows("project")).isEqualTo(projects);
        assertThat(rows("project_membership")).isEqualTo(memberships);
        assertThat(rows("project_template_definition")).isEqualTo(templates);
        assertThat(rows("attachment")).isEqualTo(attachments);
        assertThat(rows("activity_event")).singleElement().satisfies(row ->
                assertThat(row).containsEntry("event_type", "catalog.project_created"));
        assertThat(jdbc.sql("SELECT event_type FROM yumpoo.outbox_event ORDER BY event_type").query(String.class).list())
                .containsExactly("catalog.project_created", "identity.user_account_disabled");
        assertThat(count("outbox_consumer_receipt")).isEqualTo(2);
        assertThat(rows("governance_issue")).singleElement().satisfies(row ->
                assertThat(row).containsEntry("target_type", "PROJECT"));
        assertThat(rows("admin_override")).singleElement().satisfies(row ->
                assertThat(row).containsEntry("target_type", "PROJECT")
                        .containsEntry("action", "PROJECT_ARCHIVE_WITH_OPEN_ITEMS"));

        for (String scope : List.of("PRODUCT", "FEEDBACK")) {
            assertThatThrownBy(() -> insertActivity(scope, "OTHER", "other.created"))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_activity_event_scope_type");
        }
        assertThatThrownBy(() -> insertIssue("PRODUCT", UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_governance_issue_target");
        assertThatThrownBy(() -> insertOverride("PRODUCT_ARCHIVE_WITH_BLOCKERS", "PROJECT"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_admin_override_action");
        assertThatThrownBy(() -> insertOverride("PROJECT_ARCHIVE_WITH_OPEN_ITEMS", "PRODUCT"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_admin_override_target_type");
        for (String ownerType : List.of("PRODUCT_FEEDBACK", "FEEDBACK_UPDATE")) {
            assertThatThrownBy(() -> insertAttachment(ownerType))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_attachment_owner_type");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRODUCT_FEEDBACK", "FEEDBACK_UPDATE"})
    void feedbackAttachmentsAbortMigrationWithoutLosingAnyFacts(String ownerType) {
        insertAttachment(ownerType);
        List<String> tables = List.of("product", "project_product_link", "activity_event", "outbox_event",
                "outbox_consumer_receipt", "governance_issue", "admin_override", "attachment", "security_audit_event");
        var before = tables.stream().map(this::count).toList();
        var attachments = rows("attachment");
        assertThatThrownBy(() -> flyway("60").migrate())
                .hasMessageContaining("feedback attachments require explicit data resolution");
        assertThat(tables.stream().map(this::count).toList()).isEqualTo(before);
        assertThat(rows("attachment")).isEqualTo(attachments);
        assertThat(flyway("60").info().current().getVersion().getVersion()).isEqualTo("59");
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .defaultSchema("yumpoo").schemas("yumpoo").createSchemas(true)
                .target(target).validateOnMigrate(true).cleanDisabled(true).load();
    }

    private JdbcClient.StatementSpec sql(String statement) {
        return jdbc.sql(statement).param("id", UUID.randomUUID()).param("company", COMPANY)
                .param("user", USER).param("project", PROJECT).param("product", PRODUCT);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM yumpoo." + table).query(Long.class).single();
    }

    private List<Map<String, Object>> rows(String table) {
        return jdbc.sql("SELECT * FROM yumpoo." + table + " ORDER BY id").query().listOfRows();
    }

    private void insertEvent(String type) {
        UUID eventId = UUID.randomUUID();
        sql("""
                INSERT INTO yumpoo.outbox_event (event_id, event_type, event_version, aggregate_type,
                    aggregate_id, aggregate_version, company_id, actor_type, actor_user_id, occurred_at,
                    request_id, correlation_id, payload_json, status, next_attempt_at, created_at)
                VALUES (:eventId, :type, 1, 'MigrationFixture', :eventId, 0, :company, 'USER', :user, now(),
                    'up1', 'up1', '{}', 'PENDING', now(), now())
                """).param("eventId", eventId).param("type", type).update();
        sql("""
                INSERT INTO yumpoo.outbox_consumer_receipt (consumer_name, event_id, completed_at)
                VALUES ('up1-fixture', :eventId, now())
                """).param("eventId", eventId).update();
    }

    private void insertActivity(String scope, String entityType, String eventType) {
        sql("""
                INSERT INTO yumpoo.activity_event (id, event_id, projection_code, company_id, scope_type,
                    scope_id, entity_type, entity_id, event_type, actor_type, actor_user_id, actor_display_name,
                    occurred_at, template_code, entity_version, request_id, correlation_id)
                VALUES (:id, :id, 'ACTIVITY_V1', :company, :scope, :project, :entityType, :project,
                    :eventType, 'USER', :user, 'UP1 Owner', now(), 'UP1_FIXTURE', 0, 'up1', 'up1')
                """).param("scope", scope).param("entityType", entityType).param("eventType", eventType).update();
    }

    private void insertIssue(String targetType, UUID targetId) {
        sql("""
                INSERT INTO yumpoo.governance_issue (id, company_id, issue_type, target_type, target_id,
                    status, safe_summary_code, detected_event_id, detected_at)
                VALUES (:id, :company, 'OWNER_MISSING', :targetType, :targetId, 'OPEN', 'OWNER_MISSING', :id, now())
                """).param("targetType", targetType).param("targetId", targetId).update();
    }

    private void insertOverride(String action, String targetType) {
        sql("""
                INSERT INTO yumpoo.admin_override (id, company_id, action, target_type, target_id, reason,
                    request_hash, idempotency_key, actor_user_id, before_snapshot, after_snapshot,
                    blocker_counts, result, occurred_at)
                VALUES (:id, :company, :action, :targetType, :project, 'UP1 migration fixture', repeat('a',64),
                    :id, :user, '{}', '{}', '[]', 'SUCCEEDED', now())
                """).param("action", action).param("targetType", targetType).update();
    }

    private void insertAttachment(String ownerType) {
        sql("""
                INSERT INTO yumpoo.attachment (id, company_id, quota_project_id, owner_type, owner_id,
                    original_file_name, file_extension, declared_mime, status, reserved_bytes,
                    uploaded_by_user_id, intent_expires_at, created_at, updated_at)
                VALUES (:id, :company, :project, :ownerType, :project, 'fixture.txt', 'txt', 'text/plain',
                    'UPLOADING', 32, :user, now() + interval '1 day', now(), now())
                """).param("ownerType", ownerType).update();
    }
}
