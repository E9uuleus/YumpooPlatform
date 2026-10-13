package com.yumpoo.platform.reporting.application;

import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class WorkHoursAlertService {
    public static final int DEFAULT_THRESHOLD_MINUTES = 720;
    private final WorkHoursAlertRepository repository;
    private final Clock clock;
    public WorkHoursAlertService(WorkHoursAlertRepository repository, Clock clock) { this.repository = repository; this.clock = clock; }
    public record View(int dailyThresholdMinutes, String etag) {}

    @Transactional(readOnly = true)
    public View get(CurrentActor actor) {
        requireAdmin(actor);
        return repository.setting(actor.companyId()).map(s -> new View(s.dailyThresholdMinutes(), StrongEtag.format(s.rowVersion())))
                .orElse(new View(DEFAULT_THRESHOLD_MINUTES, StrongEtag.format(0)));
    }

    @Transactional
    public View update(CurrentActor actor, int minutes, long expectedVersion) {
        requireAdmin(actor);
        if (!repository.saveSetting(actor.companyId(), minutes, expectedVersion, actor.userId(), clock.instant()))
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        return get(actor);
    }

    private static void requireAdmin(CurrentActor actor) {
        if (!actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
    }
}
