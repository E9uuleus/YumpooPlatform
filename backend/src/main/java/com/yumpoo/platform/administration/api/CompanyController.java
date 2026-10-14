package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.administration.application.CompanyAdministrationService;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationSnapshot;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.UUID;

@ApiV1Controller
public final class CompanyController {

    private final CurrentActorProvider currentActorProvider;
    private final CompanyConfigurationQuery companyQuery;
    private final CompanyAdministrationService administration;
    private final IfMatchParser ifMatchParser;

    public CompanyController(
            CurrentActorProvider currentActorProvider,
            CompanyConfigurationQuery companyQuery,
            CompanyAdministrationService administration,
            IfMatchParser ifMatchParser
    ) {
        this.currentActorProvider = currentActorProvider;
        this.companyQuery = companyQuery;
        this.administration = administration;
        this.ifMatchParser = ifMatchParser;
    }

    @GetMapping("/company")
    ResponseEntity<CompanyResponse> company() {
        currentActorProvider.requiredActive();
        return respond(companyQuery.current());
    }

    @PatchMapping("/company")
    ResponseEntity<CompanyResponse> update(@Valid @RequestBody UpdateRequest request,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch) {
        CurrentActor actor = currentActorProvider.requiredActive();
        long expected = ifMatchParser.parseForVisibleResource(true, ifMatch);
        return respond(administration.rename(actor, expected, request.displayName()));
    }

    private static ResponseEntity<CompanyResponse> respond(CompanyConfigurationSnapshot company) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.format(company.rowVersion()))
                .body(CompanyResponse.from(company));
    }

    record UpdateRequest(@NotNull @Size(min = 1, max = 200) String displayName) {}

    record CompanyResponse(
            UUID id,
            String displayName,
            ZoneId timezone,
            DayOfWeek weekStartDay,
            int defaultWorkdayMinutes,
            long rowVersion
    ) {
        static CompanyResponse from(CompanyConfigurationSnapshot source) {
            return new CompanyResponse(
                    source.companyId(), source.displayName(), source.timezone(),
                    source.weekStartDay(), source.defaultWorkdayMinutes(), source.rowVersion());
        }
    }
}
