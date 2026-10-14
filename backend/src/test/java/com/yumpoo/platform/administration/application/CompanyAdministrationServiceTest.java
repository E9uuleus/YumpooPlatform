package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.organization.api.CompanyConfigurationCommand;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CompanyAdministrationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-13T02:00:00Z");
    private static final UUID COMPANY_ID = UUID.randomUUID();

    private final CompanyConfigurationQuery query = mock(CompanyConfigurationQuery.class);
    private final CompanyConfigurationCommand commands = mock(CompanyConfigurationCommand.class);
    private final SecurityAuditAppendPort audits = mock(SecurityAuditAppendPort.class);
    private final CompanyAdministrationService service = new CompanyAdministrationService(
            query, commands, audits, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    private final CurrentActor admin = new CurrentActor(UUID.randomUUID(), COMPANY_ID, 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));

    @Test
    void rejectsActorsWithoutCompanyAdmin() {
        var member = new CurrentActor(UUID.randomUUID(), COMPANY_ID, 0, Set.of());
        assertThatThrownBy(() -> service.rename(member, 0, "New"))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.errorCode()).isEqualTo(StandardErrorCode.ACCESS_DENIED));
        verifyNoInteractions(commands, audits);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankNames(String name) {
        assertThatThrownBy(() -> service.rename(admin, 0, name)).isInstanceOf(ApplicationException.class);
        verifyNoInteractions(commands, audits);
    }

    @Test
    void reportsStaleVersionsAsConflicts() {
        when(query.current()).thenReturn(company("Old", 3));
        when(commands.rename(anyString(), anyLong(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.rename(admin, 2, "New"))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.errorCode()).isEqualTo(StandardErrorCode.VERSION_CONFLICT));
        verifyNoInteractions(audits);
    }

    @Test
    void stripsNameAndAuditsTheChange() {
        when(query.current()).thenReturn(company("Old", 3), company("New Co", 4));
        when(commands.rename("New Co", 3, NOW)).thenReturn(true);

        var result = service.rename(admin, 3, "  New Co  ");

        assertThat(result.displayName()).isEqualTo("New Co");
        var draft = ArgumentCaptor.forClass(SecurityAuditDraft.class);
        verify(audits).append(draft.capture());
        assertThat(draft.getValue().action()).isEqualTo("COMPANY_RENAMED");
        assertThat(draft.getValue().beforeSummary().path("displayName").asText()).isEqualTo("Old");
        assertThat(draft.getValue().afterSummary().path("displayName").asText()).isEqualTo("New Co");
    }

    private static CompanyConfigurationSnapshot company(String name, long version) {
        return new CompanyConfigurationSnapshot(COMPANY_ID, name, ZoneId.of("Asia/Shanghai"), DayOfWeek.MONDAY, 480, version);
    }
}
