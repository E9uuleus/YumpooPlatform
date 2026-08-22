package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.administration.application.GovernanceOverrideAction;
import com.yumpoo.platform.administration.application.GovernanceOverrideCommand;
import com.yumpoo.platform.administration.application.GovernanceOverridePage;
import com.yumpoo.platform.administration.application.GovernanceOverrideResult;
import com.yumpoo.platform.administration.application.GovernanceOverrideService;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

@ApiV1Controller
public final class GovernanceOverrideController {
    private final CurrentActorProvider actors;
    private final GovernanceOverrideService service;
    private final IfMatchParser ifMatch;
    private final IdempotencyKeyParser keys;
    private final IdempotencyRequestHasher hasher;
    private final ObjectMapper objectMapper;

    public GovernanceOverrideController(CurrentActorProvider actors, GovernanceOverrideService service,
            IfMatchParser ifMatch, IdempotencyKeyParser keys, IdempotencyRequestHasher hasher,
            ObjectMapper objectMapper) {
        this.actors = actors; this.service = service; this.ifMatch = ifMatch; this.keys = keys;
        this.hasher = hasher; this.objectMapper = objectMapper;
    }

    @PostMapping("/admin/governance-overrides")
    ResponseEntity<String> create(@Valid @RequestBody GovernanceOverrideRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String keyHeader) {
        CurrentActor actor = actors.requiredActive();
        long version = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        UUID key = keys.parseRequired(keyHeader);
        StoredCommandResult result = service.override(new GovernanceOverrideCommand(actor, body.action(),
                body.targetType().strip().toUpperCase(), body.targetId(), body.reason(), version, key,
                hasher.hash("governanceOverride:" + body.action().name(),
                        Map.of("targetId", body.targetId().toString(), "ifMatch", Long.toString(version)),
                        objectMapper.valueToTree(body)))).result();
        return ProjectLifecycleGovernanceController.stored(result);
    }

    @GetMapping("/admin/governance-overrides")
    ResponseEntity<GovernanceOverridePage> list(
            @RequestParam(required = false) GovernanceOverrideAction action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) UUID targetId,
            @RequestParam(required = false) GovernanceOverrideResult result,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.findAll(actors.requiredActive(), action,
                targetType == null ? null : targetType.strip().toUpperCase(), targetId, result, offset, size));
    }
}
