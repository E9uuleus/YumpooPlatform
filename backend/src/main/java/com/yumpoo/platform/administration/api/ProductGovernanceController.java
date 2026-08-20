package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.administration.application.ProductGovernanceService;
import com.yumpoo.platform.administration.application.ProductLifecycleGovernanceCommand;
import com.yumpoo.platform.administration.application.ProductOwnerReassignmentCommand;
import com.yumpoo.platform.catalog.api.ProductSnapshot;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

@ApiV1Controller
public final class ProductGovernanceController {

    private final CurrentActorProvider currentActorProvider;
    private final ProductGovernanceService service;
    private final IfMatchParser ifMatchParser;
    private final IdempotencyKeyParser idempotencyKeyParser;
    private final IdempotencyRequestHasher requestHasher;
    private final ObjectMapper objectMapper;

    public ProductGovernanceController(
            CurrentActorProvider currentActorProvider,
            ProductGovernanceService service,
            IfMatchParser ifMatchParser,
            IdempotencyKeyParser idempotencyKeyParser,
            IdempotencyRequestHasher requestHasher,
            ObjectMapper objectMapper
    ) {
        this.currentActorProvider = currentActorProvider;
        this.service = service;
        this.ifMatchParser = ifMatchParser;
        this.idempotencyKeyParser = idempotencyKeyParser;
        this.requestHasher = requestHasher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/products/{productId}/archive")
    ResponseEntity<String> archive(
            @PathVariable UUID productId,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String idempotencyKey
    ) {
        return lifecycle(true, productId, ifMatch, idempotencyKey);
    }

    @PostMapping("/products/{productId}/restore")
    ResponseEntity<String> restore(
            @PathVariable UUID productId,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String idempotencyKey
    ) {
        return lifecycle(false, productId, ifMatch, idempotencyKey);
    }

    @PostMapping("/products/{productId}/owner-reassignments")
    ResponseEntity<String> reassignOwner(
            @PathVariable UUID productId,
            @Valid @RequestBody ProductOwnerReassignmentRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String idempotencyHeader
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        service.findForAdministration(actor, productId);
        long expectedVersion = ifMatchParser.parseForVisibleResource(true, ifMatch);
        UUID idempotencyKey = idempotencyKeyParser.parseRequired(idempotencyHeader);
        ProductOwnerReassignmentCommand command = new ProductOwnerReassignmentCommand(
                actor, productId, expectedVersion, body.newOwnerUserId(), body.reason(),
                idempotencyKey,
                requestHasher.hash("reassignProductOwner", Map.of(
                                "productId", productId.toString(),
                                "ifMatch", Long.toString(expectedVersion)),
                        objectMapper.valueToTree(body)), null, null);
        return stored(service.reassignOwner(command).result());
    }

    private ResponseEntity<String> lifecycle(
            boolean archive,
            UUID productId,
            String ifMatch,
            String idempotencyHeader
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        ProductSnapshot visible = archive
                ? service.findForArchive(actor, productId)
                : service.findForAdministration(actor, productId);
        long expectedVersion = ifMatchParser.parseForVisibleResource(visible != null, ifMatch);
        UUID idempotencyKey = idempotencyKeyParser.parseRequired(idempotencyHeader);
        String operation = archive ? "archiveProduct" : "restoreProduct";
        ProductLifecycleGovernanceCommand command = new ProductLifecycleGovernanceCommand(
                actor, productId, expectedVersion, idempotencyKey,
                requestHasher.hash(operation, Map.of(
                                "productId", productId.toString(),
                                "ifMatch", Long.toString(expectedVersion)),
                        objectMapper.createObjectNode()));
        return stored((archive ? service.archive(command) : service.restore(command)).result());
    }

    private static ResponseEntity<String> stored(StoredCommandResult stored) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setETag(stored.etag());
        headers.setCacheControl(CacheControl.noStore());
        return new ResponseEntity<>(stored.responseJson(), headers,
                HttpStatus.valueOf(stored.httpStatus()));
    }
}
