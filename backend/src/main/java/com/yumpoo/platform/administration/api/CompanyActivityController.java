package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.audit.api.ActivityItemView;
import com.yumpoo.platform.audit.api.ActivityQuery;
import com.yumpoo.platform.audit.api.ActivityQueryPort;
import com.yumpoo.platform.audit.api.CompanyActivityPage;
import com.yumpoo.platform.catalog.api.CompanyProjectQuery;
import com.yumpoo.platform.catalog.api.MemberProjectQuery;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApiV1Controller
public final class CompanyActivityController {
    private final CurrentActorProvider actors;
    private final ActivityQueryPort activity;
    private final CompanyProjectQuery projects;

    public CompanyActivityController(CurrentActorProvider actors, ActivityQueryPort activity,
            CompanyProjectQuery projects) {
        this.actors = actors;
        this.activity = activity;
        this.projects = projects;
    }

    @GetMapping("/admin/company/activity")
    ResponseEntity<Response> list(@RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            @RequestParam(name = "eventType", required = false) List<String> eventTypes,
            @RequestParam(name = "projectId", required = false) List<UUID> projectIds,
            @RequestParam(name = "actorUserId", required = false) List<UUID> actorUserIds,
            @RequestParam(required = false) Instant occurredFrom,
            @RequestParam(required = false) Instant occurredTo) {
        CurrentActor actor = actors.requiredActive();
        if (!actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        CompanyActivityPage page = activity.findCompany(actor.companyId(), projectIds, actorUserIds,
                new ActivityQuery(cursor, size, eventTypes, null, occurredFrom, occurredTo));
        Map<UUID, String> names = projects.listForAdministrator(actor).stream()
                .collect(Collectors.toMap(MemberProjectQuery.Project::id, MemberProjectQuery.Project::name));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Response(
                page.items().stream().map(item -> new Item(item.projectId(), names.get(item.projectId()), item.activity())).toList(),
                page.nextCursor(), page.historyStartedAt()));
    }

    record Response(List<Item> items, String nextCursor, Instant historyStartedAt) {}
    record Item(UUID projectId, String projectName, ActivityItemView activity) {}
}
