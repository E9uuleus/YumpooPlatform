package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.administration.application.WorkCalendarAdministrationService;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.organization.api.CalendarDaySource;
import com.yumpoo.platform.organization.api.CalendarDayType;
import com.yumpoo.platform.organization.api.CompanyCalendarCommand;
import com.yumpoo.platform.organization.api.CompanyCalendarDaySnapshot;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkCalendarControllerTest {
    static Stream<CalendarDaySource> sources() { return Stream.of(null, CalendarDaySource.MANUAL, CalendarDaySource.IMPORT); }

    @ParameterizedTest
    @MethodSource("sources")
    void preservesInheritedHoursAndSourcesAcrossWriteAndRead(CalendarDaySource inputSource) {
        var actors = mock(CurrentActorProvider.class);
        var service = mock(WorkCalendarAdministrationService.class);
        var actor = new CurrentActor(UUID.randomUUID(), UUID.randomUUID(), 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
        when(actors.requiredActive()).thenReturn(actor);
        LocalDate date = LocalDate.parse("2026-10-10");
        CalendarDaySource source = inputSource == null ? CalendarDaySource.MANUAL : inputSource;
        var inputs = List.of(new CompanyCalendarCommand.Day(date, CalendarDayType.WORKDAY, null, "补班", source));
        var year = new WorkCalendarAdministrationService.Year(2026, 480, 1, List.of(
                new CompanyCalendarDaySnapshot(date, CalendarDayType.WORKDAY, 480, true, 0, OptionalLong.of(0), "补班", null, source)));
        when(service.replace(actor, 2026, 0, inputs)).thenReturn(year);
        when(service.year(actor, 2026)).thenReturn(year);
        var controller = new WorkCalendarController(actors, service, new IfMatchParser());
        var request = new WorkCalendarController.UpdateRequest(List.of(
                new WorkCalendarController.DayInput(date, CalendarDayType.WORKDAY, null, " 补班 ", inputSource)));

        var written = controller.replace(2026, request, "\"0\"").getBody();
        var read = controller.year(2026).getBody();

        verify(service).replace(actor, 2026, 0, inputs);
        assertThat(written).isEqualTo(read);
        var day = new ObjectMapper().valueToTree(read).path("days").get(0);
        assertThat(day.path("standardMinutes").asInt()).isEqualTo(480);
        assertThat(day.has("configuredStandardMinutes")).isTrue();
        assertThat(day.path("configuredStandardMinutes").isNull()).isTrue();
        assertThat(day.path("source").asText()).isEqualTo(source.name());
    }
}
