package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.scheduling.ScheduledJobService;
import com.yumpoo.platform.foundation.application.scheduling.ScheduledJobView;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

@ApiV1Controller
public final class ScheduledJobController {
    private final CurrentActorProvider actors;
    private final ScheduledJobService service;
    private final CompanyConfigurationQuery company;
    private final IfMatchParser ifMatchParser;

    public ScheduledJobController(CurrentActorProvider actors, ScheduledJobService service,
            CompanyConfigurationQuery company, IfMatchParser ifMatchParser) {
        this.actors = actors;
        this.service = service;
        this.company = company;
        this.ifMatchParser = ifMatchParser;
    }

    @GetMapping("/admin/scheduled-jobs")
    ResponseEntity<ScheduledJobList> list() {
        CurrentActor actor = actors.requiredActive();
        if (!actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ScheduledJobList(service.list(actor.companyId(), company.current().timezone())));
    }

    @PutMapping("/admin/scheduled-jobs/{jobCode}")
    ResponseEntity<ScheduledJobView> update(@PathVariable String jobCode, @Valid @RequestBody UpdateRequest request,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch) {
        CurrentActor actor = actors.requiredActive();
        if (!actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        long expectedVersion = ifMatchParser.parseForVisibleResource(service.exists(jobCode), ifMatch);
        ScheduledJobView view = service.update(actor.companyId(), jobCode, request.enabled(), request.cron(),
                company.current().timezone(), actor.userId(), expectedVersion);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(view.etag()).body(view);
    }

    record ScheduledJobList(List<ScheduledJobView> items) {}
    record UpdateRequest(@NotNull Boolean enabled, @NotBlank @Size(max = 64) String cron) {}
}
