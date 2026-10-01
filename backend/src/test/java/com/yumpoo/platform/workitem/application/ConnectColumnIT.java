package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
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
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.ConnectionFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "yumpoo.outbox.enabled=false")
@Transactional
class ConnectColumnIT {
    @Autowired private JdbcClient jdbc;
    @Autowired private WorkItemService items;
    @Autowired private ConnectColumnService columns;
    @Autowired private ContentRepository contents;
    @Autowired private WorkItemLabelRepository labels;
    @Autowired private ObjectMapper json;
    private ConnectionFixture fixture;
    private CurrentActor owner;
    private CurrentActor member;
    private Project source;
    private Project target;

    @BeforeEach
    void setUp() {
        fixture = new ConnectionFixture(jdbc, items, columns, contents, labels, json);
        owner = fixture.user("来源负责人");
        member = fixture.user("来源成员");
        source = fixture.project(owner, "来源项目");
        target = fixture.project(fixture.user("目标负责人"), "目标项目");
        fixture.member(source.id(), member);
    }

    @Test
    void createsListsUpdatesAndReplaysColumnsWithNoOpVersionPreservation() {
        UUID key = UUID.randomUUID();
        var command = new ConnectColumnCommands.Create(member, source.id(), " 外部缺陷 ", List.of(target.id()), key, hash());
        try (var ignored = correlation()) {
            var first = columns.create(command);
            assertThat(columns.create(command).result()).isEqualTo(first.result());
            var column = json.readValue(first.result().responseJson(), ConnectColumnModels.Column.class);
            assertThat(column.name()).isEqualTo("外部缺陷");
            assertThat(column.targets().getFirst().actorCanLinkExisting()).isFalse();
            assertThat(columns.catalog(owner, source.id()).canDelete()).isTrue();
            assertThat(columns.catalog(member, source.id()).canDelete()).isFalse();
            var incoming = columns.catalog(new CurrentActor(targetOwner(), COMPANY, 0, java.util.Set.of()), target.id());
            assertThat(incoming.incomingAvailable()).isTrue();
            assertThat(incoming.incomingColumns()).singleElement().satisfies(value -> assertThat(value.columnId()).isEqualTo(column.id()));
            long events = fixture.eventCount("workitem.connect_column_updated");
            var unchanged = columns.update(new ConnectColumnCommands.Update(member, source.id(), column.id(), 0,
                    "外部缺陷", List.of(target.id())));
            assertThat(unchanged.rowVersion()).isZero();
            assertThat(fixture.eventCount("workitem.connect_column_updated")).isEqualTo(events);
            var changed = columns.update(new ConnectColumnCommands.Update(member, source.id(), column.id(), 0,
                    "变更列名", List.of(target.id())));
            assertThat(changed.rowVersion()).isEqualTo(1);
            assertThatThrownBy(() -> columns.update(new ConnectColumnCommands.Update(member, source.id(), column.id(), 0,
                    "旧版本", List.of(target.id())))).isInstanceOfSatisfying(ApplicationException.class,
                    error -> assertThat(error.errorCode()).isEqualTo(StandardErrorCode.VERSION_CONFLICT));
        }
    }

    @Test
    void rejectsDuplicateReservedNamesInvalidTargetsAndTwentyFirstColumn() {
        fixture.column(member, source, "DEFECTS", target.id());
        assertDuplicate(() -> fixture.column(member, source, "defects", target.id()));
        for (String name : List.of("被连接", "状态", "工作项名称", " ", "x".repeat(41)))
            assertThatThrownBy(() -> fixture.column(member, source, name, target.id())).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> fixture.column(member, source, "没有目标")).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> fixture.column(member, source, "自身", source.id())).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> fixture.column(member, source, "过多目标",
                java.util.stream.Stream.generate(UUID::randomUUID).limit(21).toArray(UUID[]::new))).isInstanceOf(ApplicationException.class);
        for (int i = 1; i < 20; i++) fixture.column(member, source, "列" + i, target.id());
        assertReason(() -> fixture.column(member, source, "超限", target.id()), "CONNECT_COLUMN_LIMIT");
    }

    @Test
    void unicodeNamesUseTheDatabaseNormalizationForCreationUpdatesAndDuplicates() {
        for (String name : List.of("ΟΔΟΣ", "İD", "İ".repeat(40))) {
            var column = fixture.column(member, source, name, target.id());
            String normalized = jdbc.sql("SELECT lower(:name)").param("name", name).query(String.class).single();
            assertThat(jdbc.sql("SELECT normalized_name FROM yumpoo.work_item_connect_column WHERE id=:id")
                    .param("id", column.id()).query(String.class).single()).isEqualTo(normalized);
            assertDuplicate(() -> fixture.column(member, source, normalized, target.id()));
        }
        var renamed = fixture.column(member, source, "待改名", target.id());
        try (var ignored = correlation()) {
            assertDuplicate(() -> columns.update(new ConnectColumnCommands.Update(member, source.id(), renamed.id(), 0,
                    "ΟΔΟΣ", List.of(target.id()))));
            var changed = columns.update(new ConnectColumnCommands.Update(member, source.id(), renamed.id(), 0,
                    "ΝΕΟΣ", List.of(target.id())));
            assertThat(changed.name()).isEqualTo("ΝΕΟΣ");
            String normalized = jdbc.sql("SELECT lower('ΝΕΟΣ')").query(String.class).single();
            assertThat(jdbc.sql("SELECT normalized_name FROM yumpoo.work_item_connect_column WHERE id=:id")
                    .param("id", renamed.id()).query(String.class).single()).isEqualTo(normalized);
            assertDuplicate(() -> fixture.column(member, source, normalized, target.id()));
        }
    }

    @Test
    void archivedTargetsCanBeRetainedButNotNewlyAddedAndUnusedTargetsCanBeRemoved() {
        var other = fixture.project(owner, "另一个目标");
        var column = fixture.column(member, source, "连接", target.id(), other.id());
        fixture.archive(target);
        try (var ignored = correlation()) {
            var after = columns.update(new ConnectColumnCommands.Update(member, source.id(), column.id(), 0,
                    "保留归档", List.of(target.id(), other.id())));
            assertThat(after.targets()).anySatisfy(value -> assertThat(value.lifecycle()).isEqualTo("ARCHIVED"));
            assertThat(columns.update(new ConnectColumnCommands.Update(member, source.id(), column.id(), 1,
                    "移除空目标", List.of(other.id()))).targets()).hasSize(1);
        }
        assertReason(() -> fixture.column(member, source, "新增归档目标", target.id()), "PROJECT_ARCHIVED");
    }

    @Test
    void onlyOwnerCanDeleteAndDeletionReplaysWithoutPerConnectionEvents() {
        var column = fixture.column(member, source, "待删除", target.id());
        var denied = new ConnectColumnCommands.Delete(member, source.id(), column.id(), 0, UUID.randomUUID(), hash());
        assertThatThrownBy(() -> columns.delete(denied)).isInstanceOfSatisfying(ApplicationException.class,
                error -> assertThat(error.errorCode()).isEqualTo(StandardErrorCode.ACCESS_DENIED));
        var command = new ConnectColumnCommands.Delete(owner, source.id(), column.id(), 0, UUID.randomUUID(), hash());
        try (var ignored = correlation()) {
            var deleted = columns.delete(command);
            assertThat(json.readTree(deleted.result().responseJson()).path("removedConnectionCount").asLong()).isZero();
            assertThat(columns.delete(command).result()).isEqualTo(deleted.result());
            assertThat(columns.catalog(member, source.id()).items()).isEmpty();
            assertThat(fixture.eventCount("workitem.connect_column_deleted")).isEqualTo(1);
            assertThat(fixture.eventCount("workitem.connection_deleted")).isZero();
        }
    }

    private UUID targetOwner() {
        return jdbc.sql("SELECT owner_user_id FROM yumpoo.project WHERE id=:id").param("id", target.id()).query(UUID.class).single();
    }

    private static void assertReason(Runnable operation, String reason) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApplicationException.class,
                error -> assertThat(error.reason()).isEqualTo(reason));
    }

    private static void assertDuplicate(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApplicationException.class, error -> {
            assertThat(error.errorCode()).isEqualTo(StandardErrorCode.VALIDATION_FAILED);
            assertThat(error.fieldViolations()).singleElement().satisfies(field -> {
                assertThat(field.field()).isEqualTo("name");
                assertThat(field.code()).isEqualTo("DUPLICATE");
            });
        });
    }
}
