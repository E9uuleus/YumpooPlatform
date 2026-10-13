package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.administration.application.WorkCalendarAdministrationService;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.organization.api.CalendarDayType;
import com.yumpoo.platform.organization.api.CalendarDaySource;
import com.yumpoo.platform.organization.api.CompanyCalendarCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.time.LocalDate;
import java.util.List;

@ApiV1Controller
public final class WorkCalendarController {
    private final CurrentActorProvider actors;
    private final WorkCalendarAdministrationService service;
    private final IfMatchParser ifMatchParser;

    public WorkCalendarController(CurrentActorProvider actors, WorkCalendarAdministrationService service, IfMatchParser ifMatchParser) {
        this.actors = actors; this.service = service; this.ifMatchParser = ifMatchParser;
    }

    @GetMapping("/admin/work-calendar/{year}")
    ResponseEntity<YearResponse> year(@PathVariable int year) { return respond(service.year(actors.requiredActive(), year)); }

    @PutMapping("/admin/work-calendar/{year}")
    ResponseEntity<YearResponse> replace(@PathVariable int year, @Valid @RequestBody UpdateRequest request,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch) {
        CurrentActor actor = actors.requiredActive();
        long expected = ifMatchParser.parseForVisibleResource(true, ifMatch);
        return respond(service.replace(actor, year, expected, request.days().stream().map(d -> new CompanyCalendarCommand.Day(
                d.date(), d.dayType(), d.standardMinutes(), d.note() == null || d.note().isBlank() ? null : d.note().strip(),
                d.source() == null ? CalendarDaySource.MANUAL : d.source())).toList()));
    }

    private static ResponseEntity<YearResponse> respond(WorkCalendarAdministrationService.Year view) {
        String etag = StrongEtag.format(view.version());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(etag).body(new YearResponse(view.year(),
                view.defaultWorkdayMinutes(), etag, view.days().stream()
                        .map(d -> new Day(d.date(), d.dayType(), d.standardMinutes(), d.note(), d.explicitOverride(),
                                d.configuredStandardMinutes(), d.source())).toList()));
    }

    record YearResponse(int year, int defaultWorkdayMinutes, String etag, List<Day> days) {}
    record Day(LocalDate date, CalendarDayType dayType, int standardMinutes, String note, boolean explicit,
            Integer configuredStandardMinutes, CalendarDaySource source) {}
    record UpdateRequest(@NotNull @Size(max = 366) List<@NotNull @Valid DayInput> days) {}
    record DayInput(@NotNull LocalDate date, @NotNull CalendarDayType dayType, @Min(1) @Max(720) Integer standardMinutes,
            @Size(max = 30) String note, CalendarDaySource source) {}
}
