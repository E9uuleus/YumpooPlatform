package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.catalog.api.ProjectConnectionTargetQuery;
import com.yumpoo.platform.catalog.api.ProjectConnectionTargetQuery.ConnectTargetProjectSnapshot;
import com.yumpoo.platform.catalog.api.ProjectDeletionQuery;
import com.yumpoo.platform.catalog.api.ProjectFactWriteGuard;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.workitem.domain.ConnectColumn;
import com.yumpoo.platform.workitem.domain.WorkItemConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.ConnectionFixture.COMPANY;
import static com.yumpoo.platform.workitem.application.ConnectionFixture.Project;
import static com.yumpoo.platform.workitem.application.WorkItemConnectionModels.*;
import static com.yumpoo.platform.workitem.application.WorkItemConnectionRepository.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectionPurgeVisibilityTest {
    private final CurrentActor actor=new CurrentActor(UUID.randomUUID(),COMPANY,0,Set.of());
    private final Project source=new Project(UUID.randomUUID(),UUID.randomUUID(),"SECRET-SOURCE");
    private final Project target=new Project(UUID.randomUUID(),UUID.randomUUID(),"SECRET-TARGET");
    private final UUID sourceItem=UUID.randomUUID(), targetItem=UUID.randomUUID(), columnId=UUID.randomUUID();
    private final String columnName="真实秘密连接列";
    private WorkItemConnectionRepository connections;
    private ConnectColumnRepository columns;
    private ProjectConnectionTargetQuery projects;
    private ConnectionCardReader cards;
    private ConnectColumnService directory;
    private ConnectionRow row;

    @BeforeEach void setup() {
        connections=mock(WorkItemConnectionRepository.class);
        columns=mock(ConnectColumnRepository.class);
        projects=mock(ProjectConnectionTargetQuery.class);
        var access=mock(ProjectAccessSnapshotQuery.class);
        when(access.findVisible(actor,source.id())).thenReturn(Optional.of(visible(source)));
        when(access.findVisible(actor,target.id())).thenReturn(Optional.of(visible(target)));
        // A prior visibility SELECT can still return the project while a later snapshot reports purge or disappearance.
        when(access.findVisible(eq(actor),anyCollection())).thenAnswer(invocation->{
            java.util.Collection<UUID> ids=invocation.getArgument(1);
            return java.util.stream.Stream.of(source,target).filter(project->ids.contains(project.id()))
                    .collect(java.util.stream.Collectors.toMap(Project::id,this::visible));
        });
        when(projects.findByIds(eq(COMPANY),anyCollection())).thenAnswer(invocation->{
            java.util.Collection<UUID> ids=invocation.getArgument(1);
            return java.util.stream.Stream.of(source,target).filter(project->ids.contains(project.id()))
                    .collect(java.util.stream.Collectors.toMap(Project::id,project->snapshot(project,false)));
        });
        when(connections.findCards(eq(COMPANY),anyCollection(),eq(false))).thenAnswer(invocation->{
            java.util.Collection<UUID> ids=invocation.getArgument(1);
            return java.util.stream.Stream.of(cardRow(sourceItem,source,"真实秘密来源标题"),cardRow(targetItem,target,"真实秘密目标标题"))
                    .filter(card->ids.contains(card.workItemId())).toList();
        });
        var column=ConnectColumn.create(columnId,COMPANY,source.id(),columnName,List.of(target.id()),actor.userId(),Instant.now());
        when(columns.findActive(COMPANY,source.id())).thenReturn(List.of(column));
        when(columns.findIncoming(COMPANY,target.id())).thenReturn(List.of(new ConnectColumnRepository.IncomingColumn(columnId,columnName,source.id())));
        cards=new ConnectionCardReader(connections,projects,access,mock(MinimalUserSnapshotQuery.class));
        directory=new ConnectColumnService(columns,connections,access,projects,mock(ProjectFactWriteGuard.class),
                mock(ProjectDeletionQuery.class),mock(IdempotentCommandExecutor.class),mock(TransactionalEventPort.class),
                new ObjectMapper(),Clock.systemUTC());
        row=new ConnectionRow(WorkItemConnection.create(UUID.randomUUID(),COMPANY,columnId,source.id(),sourceItem,
                target.id(),targetItem,WorkItemConnection.Origin.LINKED,actor.userId(),Instant.now()),columnName,true,0);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void missingOrPurgingSourceHidesCardsAndConnectionMetadata(boolean purging) {
        when(projects.findByIds(eq(COMPANY),anyCollection())).thenReturn(purging
                ? Map.of(source.id(),snapshot(source,true),target.id(),snapshot(target,false))
                : Map.of(target.id(),snapshot(target,false)));
        var view=cards.connections(actor,List.of(row),false).get(row.connection().id());
        assertUnavailable(view.source(),source.id(),sourceItem);
        assertThat(view.target().available()).isTrue();
        assertThat(view.columnName()).isEqualTo("不可访问的连接列");
        assertThat(view.createdBy().displayName()).isEqualTo("不可访问的成员");
        assertThat(view.capabilities().canUnlink()).isFalse();
        assertUnavailable(cards.cards(actor,List.of(sourceItem)).get(sourceItem),source.id(),sourceItem);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void missingOrPurgingTargetHidesOutgoingDirectoryFields(boolean purging) {
        when(projects.findByIds(eq(COMPANY),anyCollection())).thenReturn(purging
                ? Map.of(target.id(),snapshot(target,true)) : Map.of());
        assertThat(directory.catalog(actor,source.id()).items()).singleElement().satisfies(column->{
            assertThat(column.name()).isEqualTo(columnName);
            assertThat(column.targets()).singleElement().satisfies(value->{
                assertThat(value.projectId()).isEqualTo(target.id());
                assertThat(value.available()).isFalse();
                assertThat(value.actorCanLinkExisting()).isFalse();
                assertThat(value.code()).isEqualTo("—");
                assertThat(value.name()).isEqualTo("不可访问的项目");
            });
        });
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void missingOrPurgingSourceHidesIncomingDirectoryName(boolean purging) {
        when(projects.findByIds(eq(COMPANY),anyCollection())).thenReturn(purging
                ? Map.of(source.id(),snapshot(source,true)) : Map.of());
        assertThat(directory.catalog(actor,target.id()).incomingColumns()).singleElement().satisfies(value->{
            assertThat(value.available()).isFalse();
            assertThat(value.actorCanLinkExisting()).isFalse();
            assertThat(value.columnName()).isEqualTo("不可访问的连接列");
            assertThat(value.projectCode()).isEqualTo("—");
            assertThat(value.projectName()).isEqualTo("不可访问的项目");
        });
    }

    @Test void missingCardRowsAfterConnectionReadStillReturnUnavailableEndpoints() {
        when(connections.findCards(eq(COMPANY),anyCollection(),eq(false))).thenReturn(List.of());
        var view=cards.connections(actor,List.of(row),false).get(row.connection().id());
        assertUnavailable(view.source(),source.id(),sourceItem);
        assertUnavailable(view.target(),target.id(),targetItem);
        assertThat(view.columnName()).isEqualTo("不可访问的连接列");
        assertThat(view.createdBy().displayName()).isEqualTo("不可访问的成员");
        assertThat(view.capabilities().canUnlink()).isFalse();
    }

    private ProjectAccessSnapshot visible(Project project) {
        return new ProjectAccessSnapshot(project.id(),COMPANY,ProjectAccessSnapshot.ProjectLifecycle.ACTIVE,
                ProjectAccessSnapshot.ActorProjectAccess.OWNER,0,OptionalLong.of(0));
    }
    private ConnectTargetProjectSnapshot snapshot(Project project,boolean purging) {
        return new ConnectTargetProjectSnapshot(project.id(),project.code(),"真实秘密项目名",
                purging?ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED:ProjectAccessSnapshot.ProjectLifecycle.ACTIVE,purging);
    }
    private CardRow cardRow(UUID item,Project project,String title) {
        return new CardRow(item,project.id(),"SECRET-ITEM",title,false,
                new ConnectionCardStatus("OPEN","真实秘密状态","GRAY","TODO"),
                new ConnectionCardLabel("HIGH","真实秘密优先级","RED"),
                new ConnectionCardCategory(project.contentId(),"真实秘密目录","GRAY"),actor.userId());
    }
    private static void assertUnavailable(ConnectionCard card,UUID project,UUID item) {
        assertThat(card).isNotNull();
        assertThat(card.workItemId()).isEqualTo(item);
        assertThat(card.projectId()).isEqualTo(project);
        assertThat(card.available()).isFalse();
        assertThat(card.canOpen()).isFalse();
        assertThat(card.itemNo()).isEqualTo("—");
        assertThat(card.title()).isEqualTo("不可访问的工作项");
        assertThat(card.projectCode()).isEqualTo("—");
        assertThat(card.projectName()).isEqualTo("不可访问的项目");
        assertThat(card.status().name()).isEqualTo("不可访问");
        assertThat(card.category().name()).isEqualTo("不可访问");
        assertThat(card.priority()).isNull();
        assertThat(card.assignee()).isNull();
    }
}
