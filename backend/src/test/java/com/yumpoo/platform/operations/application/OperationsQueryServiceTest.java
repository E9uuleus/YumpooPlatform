package com.yumpoo.platform.operations.application;
import com.yumpoo.platform.identityaccess.api.*;
import com.yumpoo.platform.foundation.application.logging.LogQueryPort;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationsQueryServiceTest {
    @Test void groupsSessionsByUserAndDoesNotExposeOtherCompaniesOrCredentials() {
        Instant now=Instant.parse("2026-09-26T12:00:00Z");UUID company=UUID.randomUUID(),user=UUID.randomUUID();
        ActiveSessionQuery sessions=mock(ActiveSessionQuery.class);
        when(sessions.findActive(company, now)).thenReturn(List.of(
            session(company,user,now.minusSeconds(10),"WEB"),session(company,user,now.minusSeconds(300),"ELECTRON"),
            session(company,UUID.randomUUID(),now.minusSeconds(121),"WEB"),session(company,UUID.randomUUID(),now.minusSeconds(1801),"WEB")));
        var service=new OperationsQueryService(mock(OperationsRuntime.class),mock(OperationsRepository.class),mock(OperationsAlertService.class),sessions,mock(LogQueryPort.class),Clock.fixed(now,ZoneOffset.UTC));
        var summary=service.sessionSummary(company);
        assertThat(summary.online()).isOne();assertThat(summary.idle()).isOne();assertThat(summary.away()).isOne();assertThat(summary.activeSessions()).isEqualTo(4);
        var page=service.sessions(company,"ONLINE","ELECTRON",null,0,20);
        assertThat(page.items()).hasSize(1);assertThat(page.items().getFirst().sessions()).hasSize(2);
        verify(sessions, times(2)).findActive(company, now);
    }
    @Test void onlyEffectiveAppManagerCanReadOperations() {
        CurrentActorProvider actors=mock(CurrentActorProvider.class);var policy=new OperationsAccessPolicy(actors);
        for(Set<PlatformRoleCode> roles:List.of(Set.<PlatformRoleCode>of(),Set.of(PlatformRoleCode.COMPANY_ADMIN))) {
            when(actors.requiredActive()).thenReturn(new CurrentActor(UUID.randomUUID(),UUID.randomUUID(),0,roles));
            assertThatThrownBy(policy::requireManager).isInstanceOf(ApplicationException.class);
        }
        var manager=new CurrentActor(UUID.randomUUID(),UUID.randomUUID(),0,Set.of(PlatformRoleCode.APP_MANAGER));
        when(actors.requiredActive()).thenReturn(manager);assertThat(policy.requireManager()).isEqualTo(manager);
        verify(actors,times(3)).requiredActive();
    }
    private ActiveSessionQuery.Session session(UUID company,UUID user,Instant seen,String client) {
        return new ActiveSessionQuery.Session(UUID.randomUUID(),company,user,"成员",client,"1.0",seen.minusSeconds(1000),seen,seen.plusSeconds(10000));
    }
}

