package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import java.util.List;

/** Every project of the actor's company, archived included, for administrator reporting. */
public interface CompanyProjectQuery {
    List<MemberProjectQuery.Project> listForAdministrator(CurrentActor actor);
}
