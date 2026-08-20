package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.api.error.ApiErrorResponse;
import com.yumpoo.platform.foundation.api.error.ApiErrorDetails;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageResponse;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.request.RequestIdContext;
import com.yumpoo.platform.identityaccess.application.administration.DirectoryRunQuery;
import com.yumpoo.platform.identityaccess.application.administration.DirectorySyncFailureView;
import com.yumpoo.platform.identityaccess.application.administration.DirectorySyncRunView;
import com.yumpoo.platform.identityaccess.application.administration.IdentityAdministrationQueryService;
import com.yumpoo.platform.identityaccess.application.administration.IdentityMemberQuery;
import com.yumpoo.platform.identityaccess.application.administration.IdentityMemberView;
import com.yumpoo.platform.identityaccess.application.administration.ManualDirectorySyncService;
import com.yumpoo.platform.identityaccess.application.administration.WeComIntegrationStatusView;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncClaimDisposition;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncExecutionResult;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncRunStatus;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncTriggerType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@ApiV1Controller
public final class IdentityAdministrationController {

    private static final String RUNS_PATH = "/api/v1/admin/directory-sync-runs/";

    private final CurrentActorProvider currentActorProvider;
    private final IdentityAdministrationQueryService queryService;
    private final ManualDirectorySyncService manualSyncService;
    private final IdempotencyKeyParser idempotencyKeyParser;

    public IdentityAdministrationController(
            CurrentActorProvider currentActorProvider,
            IdentityAdministrationQueryService queryService,
            ManualDirectorySyncService manualSyncService,
            IdempotencyKeyParser idempotencyKeyParser
    ) {
        this.currentActorProvider = currentActorProvider;
        this.queryService = queryService;
        this.manualSyncService = manualSyncService;
        this.idempotencyKeyParser = idempotencyKeyParser;
    }

    @GetMapping("/admin/integrations/wecom/status")
    WeComIntegrationStatusView integrationStatus() {
        CurrentActor actor = currentActorProvider.requiredActive();
        return queryService.integrationStatus(actor.companyId(), actor.userId());
    }

    @GetMapping("/admin/members")
    OffsetPageResponse<IdentityMemberView> members(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String externalUserId,
            @RequestParam(required = false) String employmentStatus,
            @RequestParam(required = false) String accountStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        OffsetPageRequest pageRequest = OffsetPageRequest.of(page, size);
        var result = queryService.members(actor.companyId(), actor.userId(), new IdentityMemberQuery(
                name, externalUserId, employmentStatus, accountStatus, pageRequest));
        return OffsetPageResponse.of(result.items(), pageRequest, result.total());
    }

    @GetMapping("/admin/members/{userId}")
    ResponseEntity<IdentityMemberView> member(@PathVariable UUID userId) {
        CurrentActor actor = currentActorProvider.requiredActive();
        IdentityMemberView member = queryService.member(actor.companyId(), actor.userId(), userId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(member.etag())
                .body(member);
    }

    @GetMapping("/admin/directory-sync-runs")
    OffsetPageResponse<DirectorySyncRunView> runs(
            @RequestParam(required = false) DirectorySyncRunStatus status,
            @RequestParam(required = false) DirectorySyncTriggerType triggerType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        OffsetPageRequest pageRequest = OffsetPageRequest.of(page, size);
        var result = queryService.runs(actor.companyId(), actor.userId(), new DirectoryRunQuery(
                enumName(status), enumName(triggerType), pageRequest));
        return OffsetPageResponse.of(result.items(), pageRequest, result.total());
    }

    @GetMapping("/admin/directory-sync-runs/{runId}")
    DirectorySyncRunView run(@PathVariable UUID runId) {
        CurrentActor actor = currentActorProvider.requiredActive();
        return queryService.run(actor.companyId(), actor.userId(), runId);
    }

    @GetMapping("/admin/directory-sync-runs/{runId}/failures")
    OffsetPageResponse<DirectorySyncFailureView> failures(
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        OffsetPageRequest pageRequest = OffsetPageRequest.of(page, size);
        var result = queryService.failures(
                actor.companyId(), actor.userId(), runId, pageRequest);
        return OffsetPageResponse.of(result.items(), pageRequest, result.total());
    }

    @PostMapping("/admin/directory-sync-runs")
    ResponseEntity<?> trigger(
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyKey,
            HttpServletRequest request
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        UUID parsedKey = idempotencyKeyParser.parseRequired(idempotencyKey);
        String requestId = requiredRequestId(request);
        DirectorySyncExecutionResult execution = manualSyncService.execute(
                actor.companyId(), actor.userId(), parsedKey.toString(), requestId);
        URI location = URI.create(RUNS_PATH + execution.snapshot().runId());

        if (execution.disposition() == DirectorySyncClaimDisposition.ACTIVE_CONFLICT) {
            StandardErrorCode error = StandardErrorCode.INVALID_STATE_TRANSITION;
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(RequestIdContext.HEADER_NAME, requestId)
                    .location(location)
                    .body(new ApiErrorResponse(
                            error.name(), error.defaultMessage(), requestId, error.retryable(),
                            List.of(), ApiErrorDetails.EMPTY));
        }

        DirectorySyncRunView body = queryService.run(
                actor.companyId(), actor.userId(), execution.snapshot().runId());
        HttpStatus status = execution.disposition() == DirectorySyncClaimDisposition.NEW
                ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(RequestIdContext.HEADER_NAME, requestId)
                .location(location)
                .body(body);
    }

    private static String requiredRequestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdContext.ATTRIBUTE_NAME);
        return RequestIdContext.requireValid(value == null ? null : value.toString(), "requestId");
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
