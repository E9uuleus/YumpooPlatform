package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.product.ProductCreateCommand;
import com.yumpoo.platform.catalog.application.product.ProductListStatus;
import com.yumpoo.platform.catalog.application.product.ProductService;
import com.yumpoo.platform.catalog.application.product.ProductUpdateCommand;
import com.yumpoo.platform.catalog.application.product.ProductView;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@ApiV1Controller
public final class ProductController {

    private static final String PRODUCT_PATH = "/api/v1/products/";

    private final CurrentActorProvider currentActorProvider;
    private final ProductService service;
    private final IfMatchParser ifMatchParser;
    private final IdempotencyKeyParser idempotencyKeyParser;
    private final IdempotencyRequestHasher requestHasher;
    private final ObjectMapper objectMapper;

    public ProductController(
            CurrentActorProvider currentActorProvider,
            ProductService service,
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

    @GetMapping("/products")
    ResponseEntity<OffsetPageResponse<ProductView>> list(
            @RequestParam(defaultValue = "ACTIVE") ProductListStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.findAll(actor, status, OffsetPageRequest.of(page, size)));
    }

    @GetMapping("/products/{productId}")
    ResponseEntity<ProductView> detail(@PathVariable UUID productId) {
        CurrentActor actor = currentActorProvider.requiredActive();
        ProductView product = service.findVisible(actor, productId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(Long.toString(product.rowVersion()))
                .body(product);
    }

    @PostMapping("/products")
    ResponseEntity<String> create(
            @Valid @RequestBody ProductCreateRequest body,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        UUID idempotencyKey = idempotencyKeyParser.parseRequired(idempotencyHeader);
        ProductCreateCommand command = new ProductCreateCommand(
                actor, body.code(), body.name(), body.description(), body.ownerUserId(),
                idempotencyKey,
                requestHasher.hash("createProduct", Map.of(), objectMapper.valueToTree(body)));
        return stored(service.create(command).result());
    }

    @PatchMapping("/products/{productId}")
    ResponseEntity<ProductView> update(
            @PathVariable UUID productId,
            @Valid @RequestBody ProductUpdateRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        service.findVisible(actor, productId);
        long expectedVersion = ifMatchParser.parseForVisibleResource(true, ifMatch);
        ProductView product = service.update(new ProductUpdateCommand(
                actor, productId, expectedVersion, body.name(), body.description()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(Long.toString(product.rowVersion()))
                .body(product);
    }

    private static ResponseEntity<String> stored(StoredCommandResult stored) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag(stored.etag());
        headers.setLocation(URI.create(PRODUCT_PATH + stored.resourceId()));
        return new ResponseEntity<>(stored.responseJson(), headers,
                HttpStatus.valueOf(stored.httpStatus()));
    }
}
