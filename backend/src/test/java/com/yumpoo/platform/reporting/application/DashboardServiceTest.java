package com.yumpoo.platform.reporting.application;

import com.yumpoo.platform.catalog.api.MemberProjectQuery;
import com.yumpoo.platform.catalog.api.ProjectDeletionQuery;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.*;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.api.WorkItemStatisticsQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.yumpoo.platform.reporting.application.DashboardModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashboardServiceTest {
    private final UUID company=UUID.randomUUID(), user=UUID.randomUUID(), dashboard=UUID.randomUUID();
    private final UUID root=UUID.fromString("80000000-0000-4000-8000-000000000001");
    private final UUID global=UUID.fromString("00000000-0000-4000-8000-000000000002");
    private final UUID chart=UUID.fromString("ffffffff-0000-4000-8000-000000000003");
    private final UUID chartFilter=UUID.fromString("10000000-0000-4000-8000-000000000004");
    private final Instant now=Instant.parse("2026-10-10T12:00:00Z");
    private final CurrentActor actor=new CurrentActor(user,company,0,Set.of());
    private DashboardRepository repository;
    private MemberProjectQuery projects;
    private ProjectDeletionQuery deletion;
    private DashboardService service;

    @BeforeEach void setup() {
        repository=mock(DashboardRepository.class);
        projects=mock(MemberProjectQuery.class);
        deletion=mock(ProjectDeletionQuery.class);
        var statistics=mock(WorkItemStatisticsQuery.class);
        var idempotency=mock(IdempotentCommandExecutor.class);
        when(idempotency.execute(any(),any())).thenAnswer(invocation->{
            Supplier<StoredCommandResult> command=invocation.getArgument(1);
            return IdempotencyExecutionResult.executed(command.get());
        });
        when(deletion.lockForProjection(eq(company),any())).thenAnswer(invocation->Optional.of(state(invocation.getArgument(1),"ACTIVE",false)));
        when(projects.find(eq(actor),anyCollection())).thenAnswer(invocation->{
            java.util.Collection<UUID> ids=invocation.getArgument(1);
            return ids.stream().map(id->new MemberProjectQuery.Project(id,"项目","PROJECT","ACTIVE")).toList();
        });
        var json=mock(ObjectMapper.class);
        when(json.writeValueAsString(any())).thenReturn("{}");
        service=new DashboardService(repository,projects,deletion,statistics,idempotency,json,Clock.fixed(now,ZoneOffset.UTC));
    }

    @Test void locksAllPersistedProjectPathsOnceInStableOrderBeforeMembershipAndInsert() {
        var unrelatedContent=UUID.randomUUID();
        var globalFilters=new WorkItemStatisticsQuery.Filters(List.of(global,root),List.of(),List.of(),List.of(),
                List.of(unrelatedContent),List.of(),null,null,false,"",false);
        var input=new Write("看板",new Configuration(List.of(root,root),
                List.of(widget(List.of(chart,root),filters(List.of(chartFilter,global)))),globalFilters));
        assertThat(command("POST",null,input,0).result().httpStatus()).isEqualTo(201);
        var order=inOrder(deletion,projects,repository);
        for (UUID id : List.of(global,chartFilter,root,chart)) order.verify(deletion).lockForProjection(company,id);
        order.verify(projects).find(actor,List.of(root));
        order.verify(repository).insert(argThat(value->value.configuration().projectIds().equals(List.of(root))));
        verifyNoMoreInteractions(deletion);
    }

    @ParameterizedTest
    @CsvSource({"ROOT,false","ROOT,true","GLOBAL,false","GLOBAL,true","CHART,false","CHART,true","CHART_FILTER,false","CHART_FILTER,true"})
    void missingOrPurgingReferencesCannotBeWrittenBackEvenWhenAlreadyStored(Path path,boolean purging) {
        UUID removed=UUID.randomUUID();
        Write input=reference(path,removed);
        Stored before=new Stored(dashboard,company,user,input.name(),input.configuration(),7,now,now,null);
        when(repository.find(company,user,dashboard,false)).thenReturn(Optional.of(before));
        when(deletion.lockForProjection(company,removed)).thenReturn(purging?Optional.of(state(removed,"ARCHIVED",true)):Optional.empty());
        assertThatThrownBy(()->command("PATCH",dashboard,input,7)).isInstanceOfSatisfying(ApplicationException.class,
                failure->assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.RESOURCE_NOT_FOUND));
        verify(repository,never()).update(any(),anyLong());
        verify(repository,never()).insert(any());
        verifyNoInteractions(projects);
    }

    @ParameterizedTest @ValueSource(strings={"ACTIVE","ARCHIVED"})
    void existingInvisibleConnectionsRemainEditableWhileTheirProjectIsHeld(String lifecycle) {
        var input=new Write("重命名",new Configuration(List.of(root),List.of(),WorkItemStatisticsQuery.Filters.empty()));
        when(repository.find(company,user,dashboard,false)).thenReturn(Optional.of(
                new Stored(dashboard,company,user,"旧名称",input.configuration(),7,now,now,null)));
        when(projects.find(eq(actor),anyCollection())).thenReturn(List.of());
        when(deletion.lockForProjection(company,root)).thenReturn(Optional.of(state(root,lifecycle,false)));
        when(repository.update(any(),eq(7L))).thenReturn(true);
        assertThat(command("PATCH",dashboard,input,7).result().httpStatus()).isEqualTo(200);
        var order=inOrder(deletion,projects,repository);
        order.verify(repository,times(2)).find(company,user,dashboard,false);
        order.verify(deletion).lockForProjection(company,root);
        order.verify(projects).find(actor,List.of());
        order.verify(repository).update(argThat(value->value.configuration().projectIds().equals(List.of(root)) && value.version()==8),eq(7L));
    }

    @Test void newConnectionsStillRequireActiveMembership() {
        when(projects.find(actor,List.of(root))).thenReturn(List.of());
        var input=new Write("看板",new Configuration(List.of(root),List.of(),WorkItemStatisticsQuery.Filters.empty()));
        assertThatThrownBy(()->command("POST",null,input,0)).isInstanceOfSatisfying(ApplicationException.class,
                failure->assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.RESOURCE_NOT_FOUND));
        verify(deletion).lockForProjection(company,root);
        verify(repository,never()).insert(any());
    }

    @Test void deletingDashboardDoesNotRequireItsOldProjectReferencesToExist() {
        var before=new Stored(dashboard,company,user,"看板",new Configuration(List.of(root),List.of(),WorkItemStatisticsQuery.Filters.empty()),7,now,now,null);
        when(repository.find(company,user,dashboard,true)).thenReturn(Optional.of(before));
        when(repository.find(company,user,dashboard,false)).thenReturn(Optional.of(before));
        when(repository.delete(company,user,dashboard,7,now)).thenReturn(true);
        assertThat(command("DELETE",dashboard,null,7).result().httpStatus()).isEqualTo(200);
        verifyNoInteractions(deletion,projects);
    }

    private IdempotencyExecutionResult command(String method,UUID id,Write input,long version) {
        return service.command(actor,method,id,input,version,UUID.randomUUID(),new RequestHash("0".repeat(64)));
    }
    private ProjectDeletionQuery.State state(UUID project,String lifecycle,boolean purging) {
        return new ProjectDeletionQuery.State(project,company,"PROJECT",lifecycle,user,0,
                purging?now.minusSeconds(60):null,purging?user:null,purging?now.minusSeconds(1):null,purging?now:null);
    }
    private Write reference(Path path,UUID id) {
        return new Write("看板",new Configuration(path==Path.ROOT?List.of(id):List.of(root),
                path==Path.CHART || path==Path.CHART_FILTER?List.of(widget(path==Path.CHART?List.of(id):null,
                        path==Path.CHART_FILTER?filters(List.of(id)):null)):List.of(),
                path==Path.GLOBAL?filters(List.of(id)):WorkItemStatisticsQuery.Filters.empty()));
    }
    private WorkItemStatisticsQuery.Filters filters(List<UUID> ids) {
        return new WorkItemStatisticsQuery.Filters(ids,List.of(),List.of(),List.of(),List.of(),List.of(),null,null,false,"",false);
    }
    private Widget widget(List<UUID> projects,WorkItemStatisticsQuery.Filters filters) {
        return new Widget(UUID.randomUUID().toString(),"CHART","图表","TOTAL","STATUS","DESC",true,true,
                new Position(0,0,2,3),new Position(0,0,2,3),new Chart("COLUMN","PROJECT","NONE","MONTH","UTC",
                new WorkItemStatisticsQuery.ChartMeasure("TOTAL","SUM"),null,null,false,true,true,"VALUE","VALUE_DESC",0,true,
                projects,filters,List.of(),List.of("status")));
    }
    private enum Path { ROOT, GLOBAL, CHART, CHART_FILTER }
}
