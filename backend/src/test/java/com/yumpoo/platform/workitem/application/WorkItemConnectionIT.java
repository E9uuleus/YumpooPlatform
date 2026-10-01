package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.ConnectionFixture.*;
import static com.yumpoo.platform.workitem.application.WorkItemConnectionCommands.*;
import static com.yumpoo.platform.workitem.application.WorkItemConnectionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({PostgreSqlTestContainerConfiguration.class, ConnectionSqlCounter.Configuration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "yumpoo.outbox.enabled=false")
@Transactional
class WorkItemConnectionIT {
    @Autowired private JdbcClient jdbc;
    @Autowired private WorkItemService items;
    @Autowired private ConnectColumnService columns;
    @Autowired private WorkItemConnectionService connections;
    @Autowired private ContentRepository contents;
    @Autowired private WorkItemLabelRepository labels;
    @Autowired private ObjectMapper json;
    private ConnectionFixture fixture;
    private CurrentActor sourceOwner;
    private CurrentActor targetOwner;
    private CurrentActor both;
    private Project source;
    private Project target;
    private WorkItemModels.WorkItemDetail sourceItem;
    private WorkItemModels.WorkItemDetail targetItem;
    private ConnectColumnModels.Column column;

    @BeforeEach
    void setUp() {
        fixture = new ConnectionFixture(jdbc, items, columns, contents, labels, json);
        sourceOwner = fixture.user("来源负责人");
        targetOwner = fixture.user("目标负责人");
        both = fixture.user("双侧成员");
        source = fixture.project(sourceOwner, "来源项目");
        target = fixture.project(targetOwner, "目标项目");
        fixture.member(source.id(), both);
        fixture.member(target.id(), both);
        sourceItem = fixture.item(sourceOwner, source, "来源事项");
        targetItem = fixture.item(targetOwner, target, "目标事项");
        column = fixture.column(sourceOwner, source, "外部问题", target.id());
    }

    @Test
    void nonTargetMemberCreatesRootItemWithMinimalDefaultsAndBothEventsAtomically() {
        long createdBefore = fixture.eventCount("workitem.work_item_created");
        var command = new CreateConnected(sourceOwner, sourceItem.id(), column.id(), target.id(), "  跨项目反馈  ",
                null, UUID.randomUUID(), hash());
        try (var ignored = correlation()) {
            var first = connections.createConnected(command);
            var view = json.readValue(first.result().responseJson(), ConnectionView.class);
            assertThat(first.result().httpStatus()).isEqualTo(201);
            assertThat(connections.createConnected(command).result()).isEqualTo(first.result());
            assertThat(view.origin()).isEqualTo("CREATED");
            assertThat(view.target().canOpen()).isFalse();
            assertThat(view.source().canOpen()).isTrue();
            assertThat(view.capabilities().canUnlink()).isTrue();
            assertThat(view.target().title()).isEqualTo("跨项目反馈");
            assertThat(view.target().status().code()).isEqualTo("NOT_STARTED");
            assertThat(view.target().priority()).isNull();
            assertThat(view.target().assignee()).isNull();
            assertThat(view.target().category().id()).isEqualTo(target.contentId());
            var row = jdbc.sql("SELECT * FROM yumpoo.work_item WHERE id=:id")
                    .param("id", view.target().workItemId()).query().singleRow();
            for (String field : List.of("priority", "assignee_user_id", "description", "notes", "timeline_start_date",
                    "timeline_end_date", "due_date", "due_time")) assertThat(row.get(field)).as(field).isNull();
            assertThat(row.get("reporter_user_id")).isEqualTo(sourceOwner.userId());
            assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_relation WHERE right_work_item_id=:id")
                    .param("id", view.target().workItemId()).query(Long.class).single()).isZero();
            assertThat(fixture.eventCount("workitem.work_item_created")).isEqualTo(createdBefore + 1);
            assertThat(fixture.eventCount("workitem.connection_created")).isEqualTo(1);
        }
    }

    @Test
    void createRejectsOutsideColumnDisabledCategoryAndArchivedTarget() {
        assertField(() -> create(sourceOwner, UUID.randomUUID(), null), "CONNECT_TARGET_NOT_IN_COLUMN");
        jdbc.sql("UPDATE yumpoo.content SET active=false WHERE id=:id").param("id", target.contentId()).update();
        assertField(() -> create(sourceOwner, target.id(), target.contentId()), "CONTENT_NOT_ACTIVE");
        fixture.archive(target);
        assertReason(() -> create(sourceOwner, target.id(), null), "PROJECT_ARCHIVED");
        assertThat(fixture.eventCount("workitem.connection_created")).isZero();
    }

    @Test
    void linkingRequiresBothMembershipsAndReturnsSameIdForExistingPair() {
        assertCode(() -> link(sourceOwner, targetItem.id()), StandardErrorCode.ACCESS_DENIED);
        var first = link(both, targetItem.id());
        assertThat(first.httpStatus()).isEqualTo(201);
        var repeated = link(both, targetItem.id());
        assertThat(repeated.httpStatus()).isEqualTo(200);
        assertThat(repeated.resourceId()).isEqualTo(first.resourceId());
        assertThat(fixture.eventCount("workitem.connection_created")).isEqualTo(1);
    }

    @Test
    void linkingRejectsArchivedItemsAndFiftyFirstConnection() {
        jdbc.sql("UPDATE yumpoo.work_item SET archived=true WHERE id=:id").param("id", targetItem.id()).update();
        assertReason(() -> link(both, targetItem.id()), "WORK_ITEM_ARCHIVED");
        jdbc.sql("UPDATE yumpoo.work_item SET archived=false WHERE id=:id").param("id", targetItem.id()).update();
        link(both, targetItem.id());
        for (int i = 1; i < 50; i++) link(both, fixture.item(targetOwner, target, "目标" + i).id());
        var extra = fixture.item(targetOwner, target, "第 51 条");
        assertReason(() -> link(both, extra.id()), "CONNECTION_LIMIT");
        assertThat(link(both, targetItem.id()).httpStatus()).isEqualTo(200);
    }

    @Test
    void eitherEndpointMemberCanUnlinkButReadOnlyAdminCannotAndVersionsAreChecked() {
        var linked = link(both, targetItem.id());
        var adminUser = fixture.user("只读管理员");
        var admin = new CurrentActor(adminUser.userId(), COMPANY, 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
        assertCode(() -> unlink(admin, linked.resourceId(), 0), StandardErrorCode.ACCESS_DENIED);
        assertCode(() -> unlink(sourceOwner, linked.resourceId(), 7), StandardErrorCode.VERSION_CONFLICT);
        var deleted = unlink(targetOwner, linked.resourceId(), 0);
        assertThat(json.readTree(deleted.responseJson()).path("active").asBoolean()).isFalse();
        assertReason(() -> unlink(sourceOwner, linked.resourceId(), 1), "CONNECTION_NOT_ACTIVE");
        assertThat(unlink(sourceOwner, link(both, targetItem.id()).resourceId(), 0).httpStatus()).isEqualTo(200);
    }

    @Test
    void unlinkRejectsArchivedProjectsAndStillWorksWhenAnEndpointWasDeleted() {
        var linked = link(both, targetItem.id());
        fixture.archive(target);
        assertReason(() -> unlink(sourceOwner, linked.resourceId(), 0), "PROJECT_ARCHIVED");
        jdbc.sql("UPDATE yumpoo.project SET lifecycle='ACTIVE', archived_at=NULL WHERE id=:id").param("id", target.id()).update();
        deleteItem(targetItem.id(), true);
        assertThat(unlink(sourceOwner, linked.resourceId(), 0).httpStatus()).isEqualTo(200);
    }

    @Test
    void cardsExposeExactlyAllowedFieldsHideDeletedEndpointsAndIgnoreForeignItemIds() {
        var linked = link(both, targetItem.id());
        var read = connections.find(sourceOwner, linked.resourceId());
        Set<String> expected = Set.of("workItemId", "itemNo", "title", "archived", "projectId", "projectCode",
                "projectName", "projectLifecycle", "status", "priority", "category", "assignee", "canOpen");
        assertThat(json.valueToTree(read.target()).propertyNames()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(read.target().canOpen()).isFalse();
        var cells = connections.cells(sourceOwner, source.id(), List.of(sourceItem.id(), targetItem.id(), UUID.randomUUID()));
        assertThat(cells.items()).singleElement().satisfies(cell -> {
            assertThat(cell.workItemId()).isEqualTo(sourceItem.id());
            assertThat(cell.outgoing()).hasSize(1);
        });
        assertCode(() -> connections.find(fixture.user("旁观者"), linked.resourceId()), StandardErrorCode.RESOURCE_NOT_FOUND);
        deleteItem(targetItem.id(), true);
        assertThat(connections.cells(sourceOwner, source.id(), List.of(sourceItem.id())).items().getFirst().outgoing()).isEmpty();
        assertCode(() -> connections.find(sourceOwner, linked.resourceId()), StandardErrorCode.RESOURCE_NOT_FOUND);
        deleteItem(targetItem.id(), false);
        assertThat(connections.find(sourceOwner, linked.resourceId()).id()).isEqualTo(linked.resourceId());
    }

    @Test
    void incomingListFiltersInvisibleSourcesBeforeCountingAndCellsCapAtFifty() {
        for (int i = 0; i < 51; i++) {
            var item = fixture.item(sourceOwner, source, "入站来源" + i);
            try (var ignored = correlation()) {
                connections.link(new Link(both, item.id(), column.id(), targetItem.id(), UUID.randomUUID(), hash()));
            }
        }
        var thirdOwner = fixture.user("其他来源负责人");
        var thirdProject = fixture.project(thirdOwner, "其他来源项目");
        fixture.member(target.id(), thirdOwner);
        var thirdItem = fixture.item(thirdOwner, thirdProject, "其他来源事项");
        var thirdColumn = fixture.column(thirdOwner, thirdProject, "其他列", target.id());
        try (var ignored = correlation()) {
            connections.link(new Link(thirdOwner, thirdItem.id(), thirdColumn.id(), targetItem.id(), UUID.randomUUID(), hash()));
        }
        var cell = connections.cells(targetOwner, target.id(), List.of(targetItem.id())).items().getFirst();
        assertThat(cell.incoming()).hasSize(50);
        assertThat(cell.incomingTotal()).isEqualTo(52);
        var full = connections.incoming(targetOwner, targetItem.id(), new OffsetPageRequest(1, 50));
        assertThat(full.totalElements()).isEqualTo(52);
        assertThat(full.items()).hasSize(2);
        var sourceOnly = connections.incoming(sourceOwner, targetItem.id(), new OffsetPageRequest(0, 50));
        assertThat(sourceOnly.totalElements()).isEqualTo(51);
        assertThat(sourceOnly.items()).allSatisfy(value -> assertThat(value.source().projectId()).isEqualTo(source.id()));
    }

    @Test
    void optionsAndCandidatesRespectMembershipCategoryAndAlreadyConnectedState() {
        var options = connections.createOptions(sourceOwner, source.id(), column.id(), target.id());
        assertThat(options.defaultContentId()).isEqualTo(target.contentId());
        assertThat(options.categories()).hasSize(2);
        assertCode(() -> connections.candidates(sourceOwner, source.id(), column.id(), target.id(), sourceItem.id(),
                "目标", new OffsetPageRequest(0, 20)), StandardErrorCode.ACCESS_DENIED);
        link(both, targetItem.id());
        var candidates = connections.candidates(both, source.id(), column.id(), target.id(), sourceItem.id(),
                targetItem.itemNo(), new OffsetPageRequest(0, 20));
        assertThat(candidates.items()).singleElement().satisfies(value -> {
            assertThat(value.alreadyConnected()).isTrue();
            assertThat(value.card().workItemId()).isEqualTo(targetItem.id());
        });
        try (var ignored = correlation()) {
            var childResult = items.createSubitem(new WorkItemCommands.CreateSubitem(targetOwner, targetItem.id(), target.contentId(),
                    "候选子项", null, null, null, null, null, null, null, UUID.randomUUID(), hash(), DueTimeChange.unchanged()));
            var children = connections.candidates(both, source.id(), column.id(), target.id(), sourceItem.id(),
                    "候选子项", new OffsetPageRequest(0, 20));
            assertThat(children.items()).singleElement().satisfies(value -> {
                assertThat(value.card().workItemId()).isEqualTo(childResult.result().resourceId());
                assertThat(value.parent().workItemId()).isEqualTo(targetItem.id());
            });
        }
    }

    @Test
    void usedTargetCannotBeRemovedAndOwnerColumnDeleteSoftDeletesConnectionsOnce() {
        var other = fixture.project(sourceOwner, "备用目标");
        try (var ignored = correlation()) {
            columns.update(new ConnectColumnCommands.Update(sourceOwner, source.id(), column.id(), 0,
                    column.name(), List.of(target.id(), other.id())));
        }
        var linked = link(both, targetItem.id());
        try (var ignored = correlation()) {
            assertThatThrownBy(() -> columns.update(new ConnectColumnCommands.Update(sourceOwner, source.id(), column.id(), 1,
                    column.name(), List.of(other.id())))).isInstanceOfSatisfying(ApplicationException.class, error -> {
                        assertThat(error.reason()).isEqualTo("CONNECT_TARGET_IN_USE");
                        assertThat(error.connectionTargetUse().targetProjectId()).isEqualTo(target.id());
                        assertThat(error.connectionTargetUse().activeConnectionCount()).isEqualTo(1);
                    });
            var deleted = columns.delete(new ConnectColumnCommands.Delete(sourceOwner, source.id(), column.id(), 1,
                    UUID.randomUUID(), hash()));
            assertThat(json.readTree(deleted.result().responseJson()).path("removedConnectionCount").asLong()).isEqualTo(1);
        }
        assertThat(jdbc.sql("SELECT delete_reason FROM yumpoo.work_item_connection WHERE id=:id")
                .param("id", linked.resourceId()).query(String.class).single()).isEqualTo("COLUMN_DELETED");
        assertThat(fixture.eventCount("workitem.connection_deleted")).isZero();
        assertThat(columns.catalog(targetOwner, target.id()).incomingAvailable()).isFalse();
        assertThat(items.find(targetOwner, targetItem.id()).id()).isEqualTo(targetItem.id());
    }

    @Test
    void oneHundredRowsUseTheSameEightSqlStatementsAsOneRow() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            var item = fixture.item(sourceOwner, source, "批量事项" + i);
            ids.add(item.id());
            try (var ignored = correlation()) {
                connections.link(new Link(both, item.id(), column.id(), targetItem.id(), UUID.randomUUID(), hash()));
            }
        }
        int one = ConnectionSqlCounter.count(() -> assertThat(connections.cells(sourceOwner, source.id(), List.of(ids.getFirst())).items()).hasSize(1));
        int hundred = ConnectionSqlCounter.count(() -> assertThat(connections.cells(sourceOwner, source.id(), ids).items())
                .hasSize(100).allSatisfy(cell -> assertThat(cell.outgoing()).hasSize(1)));
        assertThat(one).isEqualTo(8);
        assertThat(hundred).isEqualTo(one);
    }

    private com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult create(CurrentActor actor, UUID targetId, UUID contentId) {
        try (var ignored = correlation()) {
            return connections.createConnected(new CreateConnected(actor, sourceItem.id(), column.id(), targetId,
                    "新建", contentId, UUID.randomUUID(), hash())).result();
        }
    }

    private com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult link(CurrentActor actor, UUID targetId) {
        try (var ignored = correlation()) {
            return connections.link(new Link(actor, sourceItem.id(), column.id(), targetId, UUID.randomUUID(), hash())).result();
        }
    }

    private com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult unlink(CurrentActor actor, UUID id, long version) {
        try (var ignored = correlation()) {
            return connections.unlink(new Unlink(actor, id, version, UUID.randomUUID(), hash())).result();
        }
    }

    private void deleteItem(UUID id, boolean deleted) {
        jdbc.sql(deleted ? "UPDATE yumpoo.work_item SET deleted_at=clock_timestamp(), deleted_by_user_id=:actor, delete_reason='fixture' WHERE id=:id"
                        : "UPDATE yumpoo.work_item SET deleted_at=NULL, deleted_by_user_id=NULL, delete_reason=NULL WHERE id=:id")
                .param("id", id).param("actor", targetOwner.userId()).update();
    }

    private static void assertReason(Runnable operation, String reason) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApplicationException.class, error -> assertThat(error.reason()).isEqualTo(reason));
    }
    private static void assertCode(Runnable operation, StandardErrorCode code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApplicationException.class, error -> assertThat(error.errorCode()).isEqualTo(code));
    }
    private static void assertField(Runnable operation, String code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApplicationException.class,
                error -> assertThat(error.fieldViolations()).anySatisfy(field -> assertThat(field.code()).isEqualTo(code)));
    }
}
