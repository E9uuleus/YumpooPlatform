package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.api.pagination.CursorPageRequest;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static com.yumpoo.platform.workitem.application.ConnectionFixture.*;
import static org.assertj.core.api.Assertions.*;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "yumpoo.outbox.enabled=false")
@Transactional
class ConnectionFilterIT {
    @Autowired JdbcClient jdbc;
    @Autowired WorkItemService items;
    @Autowired WorkItemRepository repository;
    @Autowired ConnectColumnService columns;
    @Autowired WorkItemConnectionService connections;
    @Autowired ContentRepository contents;
    @Autowired WorkItemLabelRepository labels;
    @Autowired ObjectMapper json;
    ConnectionFixture fixture;
    CurrentActor sourceOwner, targetOwner, both;
    Project source, target;
    WorkItemModels.WorkItemDetail first, second, empty, targetFirst, targetSecond;
    ConnectColumnModels.Column column, another;

    @BeforeEach void setUp() {
        fixture = new ConnectionFixture(jdbc, items, columns, contents, labels, json);
        sourceOwner = fixture.user("来源负责人"); targetOwner = fixture.user("目标负责人"); both = fixture.user("双侧成员");
        source = fixture.project(sourceOwner, "来源项目"); target = fixture.project(targetOwner, "目标项目");
        fixture.member(source.id(), both); fixture.member(target.id(), both);
        first = fixture.item(sourceOwner, source, "第一项"); second = fixture.item(sourceOwner, source, "第二项");
        empty = fixture.item(sourceOwner, source, "空连接项");
        targetFirst = fixture.item(targetOwner, target, "目标一"); targetSecond = fixture.item(targetOwner, target, "目标二");
        column = fixture.column(sourceOwner, source, "外部需求", target.id());
        another = fixture.column(sourceOwner, source, "另一个连接列", target.id());
        link(column.id(), first.id(), targetFirst.id()); link(another.id(), first.id(), targetFirst.id());
        link(column.id(), second.id(), targetSecond.id());
    }

    @Test void intersectsEveryColumnAndAppliesPredicatesBeforeCounting() {
        var connected = request(Set.of(column.id()), Set.of(), Set.of());
        assertThat(list(sourceOwner, source, connected)).containsExactly(first.id(), second.id());
        assertThat(list(sourceOwner, source, request(Set.of(column.id(), another.id()), Set.of(), Set.of())))
                .containsExactly(first.id());
        assertThat(list(sourceOwner, source, request(Set.of(), Set.of(column.id()), Set.of()))).containsExactly(empty.id());
        assertThat(list(sourceOwner, source, request(Set.of(column.id()), Set.of(another.id()), Set.of()))).containsExactly(second.id());
        assertThat(repository.countProjectPage(COMPANY, source.id(), WorkItemQuery.parse(connected, Set.of("NOT_STARTED"))))
                .isEqualTo(2);
        var options = items.listProjectFilterOptions(sourceOwner, source.id(), "CONTENT", connected, CursorPageRequest.of(null, 100));
        assertThat(options.items()).singleElement().satisfies(option -> assertThat(option.count()).isEqualTo(2));
    }

    @Test void incomingCountsItemsOnceAcrossColumnsWithoutRequiringSourceMembership() {
        var incoming = request(Set.of(), Set.of(), Set.of(source.id()));
        assertThat(list(targetOwner, target, incoming)).containsExactly(targetFirst.id(), targetSecond.id());
        var options = items.listProjectFilterOptions(targetOwner, target.id(), "INCOMING_PROJECT",
                request(Set.of(), Set.of(), Set.of()), CursorPageRequest.of(null, 100));
        assertThat(options.items()).singleElement().satisfies(option -> {
            assertThat(option.value()).isEqualTo(source.id().toString());
            assertThat(option.label()).isEqualTo("来源项目");
            assertThat(option.count()).isEqualTo(2);
        });
        assertThat(list(targetOwner, target, request(Set.of(), Set.of(), Set.of(UUID.randomUUID())))).isEmpty();
    }

    @Test void cursorBindsConnectionFiltersAndNormalizesTheirOrder() {
        var connected = request(Set.of(column.id()), Set.of(), Set.of());
        var page = items.listProject(sourceOwner, source.id(), connected, "TABLE", CursorPageRequest.of(null, 1));
        assertThat(page.items()).extracting(WorkItemModels.ProjectWorkItemListItem::id).containsExactly(first.id());
        assertThat(items.listProject(sourceOwner, source.id(), connected, "TABLE", CursorPageRequest.of(page.nextCursor(), 1)).items())
                .extracting(WorkItemModels.ProjectWorkItemListItem::id).containsExactly(second.id());
        assertThatThrownBy(() -> items.listProject(sourceOwner, source.id(), request(Set.of(another.id()), Set.of(), Set.of()),
                "TABLE", CursorPageRequest.of(page.nextCursor(), 1))).isInstanceOf(ApplicationException.class);
        var one = request(Set.of(column.id(), another.id()), Set.of(), Set.of());
        var reverse = request(new java.util.LinkedHashSet<>(List.of(another.id(), column.id())), Set.of(), Set.of());
        assertThat(one.connections().fingerprint()).isEqualTo(reverse.connections().fingerprint());
        assertThat(one.withTime(new WorkItemQuery.TimeFilter(null, null, null, null, 0, 0)).connections()).isEqualTo(one.connections());
    }

    @Test void excludesDeletedEndpointsAndColumnsButKeepsArchivedCounterparts() {
        var connected = request(Set.of(column.id()), Set.of(), Set.of());
        fixture.archive(target);
        assertThat(list(sourceOwner, source, connected)).hasSize(2);
        jdbc.sql("UPDATE yumpoo.work_item SET deleted_at=clock_timestamp(), deleted_by_user_id=:user, delete_reason='fixture' WHERE id=:id")
                .param("user", targetOwner.userId()).param("id", targetFirst.id()).update();
        assertThat(list(sourceOwner, source, connected)).containsExactly(second.id());
        assertThat(list(sourceOwner, source, request(Set.of(), Set.of(column.id()), Set.of())))
                .containsExactly(first.id(), empty.id());
        jdbc.sql("UPDATE yumpoo.work_item SET deleted_at=NULL, deleted_by_user_id=NULL, delete_reason=NULL WHERE id=:id")
                .param("id", targetFirst.id()).update();
        assertThat(list(sourceOwner, source, connected)).hasSize(2);
        jdbc.sql("UPDATE yumpoo.work_item_connect_column SET deleted_at=clock_timestamp(), deleted_by_user_id=:user WHERE id=:id")
                .param("user", sourceOwner.userId()).param("id", column.id()).update();
        assertThat(list(sourceOwner, source, connected)).isEmpty();
    }

    @Test void unknownOrForeignColumnsDoNotMatchAndInvisibleProjectsStayHidden() {
        assertThat(list(targetOwner, target, request(Set.of(), Set.of(column.id()), Set.of()))).isEmpty();
        assertThat(list(sourceOwner, source, request(Set.of(), Set.of(UUID.randomUUID()), Set.of()))).isEmpty();
        assertThatThrownBy(() -> list(sourceOwner, target, request(Set.of(), Set.of(), Set.of(source.id()))))
                .isInstanceOf(ApplicationException.class);
    }

    private void link(UUID columnId, UUID sourceId, UUID targetId) {
        try (var ignored = correlation()) {
            connections.link(new WorkItemConnectionCommands.Link(both, sourceId, columnId, targetId, UUID.randomUUID(), hash()));
        }
    }
    private WorkItemQuery.Request request(Set<UUID> connected, Set<UUID> unconnected, Set<UUID> incoming) {
        return new WorkItemQuery.Request(null, null, null, null, null, null, null, null, List.of("ITEM_NO,ASC"))
                .withConnections(connected, unconnected, incoming);
    }
    private List<UUID> list(CurrentActor actor, Project project, WorkItemQuery.Request request) {
        return items.listProject(actor, project.id(), request, "TABLE", CursorPageRequest.of(null, 100)).items()
                .stream().map(WorkItemModels.ProjectWorkItemListItem::id).toList();
    }
}
