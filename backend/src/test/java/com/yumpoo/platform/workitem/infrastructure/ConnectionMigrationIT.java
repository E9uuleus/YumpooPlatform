package com.yumpoo.platform.workitem.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.KanbanRank;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemRelation;
import com.yumpoo.platform.workitem.domain.WorkItemRelationType;
import com.yumpoo.platform.workitem.domain.WorkItemStatusCategory;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ConnectionMigrationIT {
    private static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");
    @Container
    private final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:17.10-alpine"));
    private DriverManagerDataSource dataSource;
    private JdbcClient jdbc;
    private UUID owner;
    private UUID project;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = JdbcClient.create(dataSource);
        flyway("59").migrate();
        owner = UUID.randomUUID();
        project = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.identity_user (id, company_id, employment_status, account_status,
                    display_name, directory_synced_at, row_version, created_at, updated_at)
                VALUES (:owner, :company, 'ACTIVE', 'ENABLED', '迁移负责人', now(), 0, now(), now())
                """).param("owner", owner).param("company", COMPANY).update();
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            sql("""
                    INSERT INTO yumpoo.project (id, company_id, workspace_id, project_code, name, project_type,
                        lifecycle, owner_user_id, template_key, template_version, row_version,
                        created_at, created_by_user_id, updated_at, updated_by_user_id)
                    SELECT :project, :company, id, 'LEGACY', '存量项目', 'PRODUCT_DEVELOPMENT',
                        'DRAFT', :owner, 'RND', 1, 0, now(), :owner, now(), :owner
                    FROM yumpoo.workspace WHERE company_id=:company AND code='MAIN'
                    """).update();
            sql("""
                    INSERT INTO yumpoo.project_membership (id, company_id, project_id, user_id, status,
                        joined_at, joined_by_user_id, row_version)
                    VALUES (:id, :company, :project, :owner, 'ACTIVE', now(), :owner, 0)
                    """).update();
        });
        UUID product = UUID.randomUUID();
        sql("""
                INSERT INTO yumpoo.product (id, company_id, product_code, name, status, owner_user_id,
                    row_version, created_at, created_by_user_id, updated_at, updated_by_user_id)
                VALUES (:product, :company, 'LEGACY_PRODUCT', '存量产品', 'ACTIVE', :owner, 0, now(), :owner, now(), :owner)
                """).param("product", product).update();
        sql("""
                INSERT INTO yumpoo.project_product_link (id, company_id, project_id, product_id, relation_type,
                    is_primary, linked_at, linked_by_user_id, updated_at, updated_by_user_id, row_version)
                VALUES (:id, :company, :project, :product, 'DEVELOPMENT', true, now(), :owner, now(), :owner, 0)
                """).param("product", product).update();
        sql("""
                INSERT INTO yumpoo.activity_event (id, event_id, projection_code, company_id, scope_type,
                    scope_id, entity_type, entity_id, event_type, actor_type, actor_user_id, actor_display_name,
                    occurred_at, template_code, entity_version, request_id, correlation_id)
                VALUES (:id, :id, 'ACTIVITY_V1', :company, 'PRODUCT', :project, 'PRODUCT', :project,
                    'catalog.product_created', 'USER', :owner, '迁移负责人', now(), 'MIGRATION', 0, 'up3', 'up3')
                """).update();
        flyway("61").migrate();
    }

    @Test
    void referenceTablesExposeCompanyScopedKeys() throws Exception {
        for (String table : new String[] { "project", "identity_user", "work_item" }) {
            var result = postgres.execInContainer("psql", "-U", postgres.getUsername(), "-d",
                    postgres.getDatabaseName(), "-c", "\\d yumpoo." + table);
            assertThat(result.getExitCode()).isZero();
            System.out.println(result.getStdout());
            assertThat(result.getStdout()).contains("company_id", "PRIMARY KEY");
        }
        assertThat(jdbc.sql("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid='yumpoo.work_item'::regclass AND conname='uq_work_item_relation_scope'
                """).query(String.class).single()).isEqualTo("UNIQUE (id, company_id, project_id)");
    }

    @Test
    void appendsV62WithoutChangingExistingProjectsItemsRelationsOrLabels() {
        Instant now = Instant.now();
        UUID contentId = UUID.randomUUID();
        var contents = new JdbcContentRepository(jdbc);
        contents.initializeCatalog(COMPANY, project, now);
        contents.insert(Content.initial(contentId, COMPANY, project, "TASKS", "保留任务", "BRIGHT_GREEN", 10, owner, now));
        new JdbcWorkItemLabelRepository(jdbc).initialize(COMPANY, project, now);
        var items = new JdbcWorkItemRepository(jdbc);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (int i = 1; i <= 2; i++) {
            UUID id = i == 1 ? first : second;
            items.insert(WorkItem.create(id, COMPANY, project, contentId, i, "LEGACY-" + i,
                    "保留事项" + i, "NOT_STARTED", WorkItemStatusCategory.TODO, null, null, null, null,
                    null, null, null, KanbanRank.evenlySpaced(i, 2),
                    com.yumpoo.platform.workitem.domain.ProjectSortKey.evenlySpaced(i, 2), owner, now));
        }
        new JdbcWorkItemRelationRepository(jdbc).insert(WorkItemRelation.create(UUID.randomUUID(), COMPANY,
                WorkItemRelationType.RELATED, first, second, project, project, owner, now));
        Map<String, List<Map<String, Object>>> before = new java.util.LinkedHashMap<>();
        for (String table : List.of("project", "project_membership", "work_item", "work_item_relation", "content",
                "project_work_item_status_label", "project_work_item_priority_label", "activity_event"))
            before.put(table, jdbc.sql("SELECT * FROM yumpoo." + table).query().listOfRows());
        assertThat(flyway("62").migrate().migrationsExecuted).isEqualTo(1);
        before.forEach((table, rows) -> assertThat(jdbc.sql("SELECT * FROM yumpoo." + table).query().listOfRows())
                .as(table).containsExactlyInAnyOrderElementsOf(rows));
        for (String table : List.of("work_item_connect_column_catalog", "work_item_connect_column",
                "work_item_connect_column_target", "work_item_connection"))
            assertThat(jdbc.sql("SELECT count(*) FROM yumpoo." + table).query(Long.class).single()).isZero();
        assertThat(flyway("62").migrate().migrationsExecuted).isZero();
    }

    private JdbcClient.StatementSpec sql(String statement) {
        return jdbc.sql(statement).param("company", COMPANY).param("owner", owner)
                .param("project", project).param("id", UUID.randomUUID());
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas("yumpoo").defaultSchema("yumpoo")
                .locations("classpath:db/migration")
                .target(target).load();
    }
}
