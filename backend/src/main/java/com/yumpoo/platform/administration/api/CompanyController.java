package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationSnapshot;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.UUID;

@ApiV1Controller
public final class CompanyController {

    private final CurrentActorProvider currentActorProvider;
    private final CompanyConfigurationQuery companyQuery;

    public CompanyController(
            CurrentActorProvider currentActorProvider,
            CompanyConfigurationQuery companyQuery
    ) {
        this.currentActorProvider = currentActorProvider;
        this.companyQuery = companyQuery;
    }

    @GetMapping("/company")
    ResponseEntity<CompanyResponse> company() {
        currentActorProvider.requiredActive();
        CompanyConfigurationSnapshot company = companyQuery.current();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(Long.toString(company.rowVersion()))
                .body(CompanyResponse.from(company));
    }

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
