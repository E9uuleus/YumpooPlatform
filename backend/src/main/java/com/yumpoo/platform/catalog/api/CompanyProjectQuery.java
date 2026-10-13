package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import java.util.List;
import java.util.UUID;

/** Every project of the actor's company, archived included, for administrator reporting. */
public interface CompanyProjectQuery {
    List<MemberProjectQuery.Project> listForAdministrator(CurrentActor actor);
    /** Scheduler only: no actor check, results must never reach an HTTP caller. */
    List<MemberProjectQuery.Project> listForScheduler(UUID companyId);
}
