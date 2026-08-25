package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.administration.application.AttachmentApplicationService;
import com.yumpoo.platform.administration.application.AttachmentIntentCommand;
import com.yumpoo.platform.filestorage.api.AttachmentModels.AttachmentMetadata;
import com.yumpoo.platform.filestorage.api.AttachmentModels.AttachmentPage;
import com.yumpoo.platform.filestorage.api.AttachmentOwnerType;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

@ApiV1Controller
public final class AttachmentController {
    private final CurrentActorProvider actors;
    private final AttachmentApplicationService service;
    private final IdempotencyKeyParser keys;
    private final IdempotencyRequestHasher hasher;
    private final IfMatchParser ifMatch;
    private final ObjectMapper objectMapper;

    public AttachmentController(CurrentActorProvider actors, AttachmentApplicationService service,
            IdempotencyKeyParser keys, IdempotencyRequestHasher hasher, IfMatchParser ifMatch,
            ObjectMapper objectMapper) {
        this.actors=actors; this.service=service; this.keys=keys; this.hasher=hasher;
        this.ifMatch=ifMatch; this.objectMapper=objectMapper;
    }

    @PostMapping("/attachments")
    ResponseEntity<String> create(@Valid @RequestBody AttachmentIntentCreateRequest body,
            @RequestHeader(name=IdempotencyKeyParser.HEADER_NAME, required=false) String header) {
        CurrentActor actor=actors.requiredActive(); UUID key=keys.parseRequired(header);
        StoredCommandResult stored=service.create(actor,new AttachmentIntentCommand(body.ownerType(),
                body.ownerId(),body.originalFileName(),body.declaredMime(),body.sizeBytes()),key,
                hasher.hash("createAttachmentIntent", Map.of(), objectMapper.valueToTree(body))).result();
        return stored(stored,HttpStatus.CREATED,"/api/v1/attachments/"+stored.resourceId());
    }

    @PutMapping(path="/attachments/{attachmentId}/content",
            consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<AttachmentMetadata> upload(@PathVariable UUID attachmentId,
            HttpServletRequest request) throws IOException {
        long length=request.getContentLengthLong();
        AttachmentMetadata metadata=service.upload(actors.requiredActive(),attachmentId,
                request.getInputStream(),length < 0 ? OptionalLong.empty() : OptionalLong.of(length));
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .eTag(Long.toString(metadata.rowVersion())).body(metadata);
    }

    @GetMapping("/attachments/{attachmentId}")
    ResponseEntity<AttachmentMetadata> find(@PathVariable UUID attachmentId) {
        AttachmentMetadata metadata=service.find(actors.requiredActive(),attachmentId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(Long.toString(metadata.rowVersion())).body(metadata);
    }

    @GetMapping("/work-items/{workItemId}/attachments")
    ResponseEntity<AttachmentPage> listWorkItem(@PathVariable UUID workItemId,
            @RequestParam(required=false) String cursor,@RequestParam(required=false) Integer size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(actors.requiredActive(),AttachmentOwnerType.WORK_ITEM,workItemId,cursor,size));
    }

    @GetMapping("/work-item-updates/{updateId}/attachments")
    ResponseEntity<AttachmentPage> listUpdate(@PathVariable UUID updateId,
            @RequestParam(required=false) String cursor,@RequestParam(required=false) Integer size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(actors.requiredActive(),AttachmentOwnerType.WORK_ITEM_UPDATE,updateId,cursor,size));
    }

    @PostMapping("/admin/attachments/{attachmentId}/rescan")
    ResponseEntity<String> rescan(@PathVariable UUID attachmentId,
            @Valid @RequestBody AttachmentRescanRequest body,
            @RequestHeader(name=IdempotencyKeyParser.HEADER_NAME,required=false) String keyHeader,
            @RequestHeader(name=IfMatchParser.HEADER_NAME,required=false) String ifMatchHeader) {
        CurrentActor actor=actors.requiredActive(); UUID key=keys.parseRequired(keyHeader);
        try {
            service.requireRescanTarget(actor,attachmentId);
            long version=ifMatch.parseForVisibleResource(true,ifMatchHeader);
            StoredCommandResult stored=service.rescan(actor,attachmentId,version,body.reason(),key,
                    hasher.hash("rescanAttachment",Map.of("attachmentId",attachmentId.toString()),
                            objectMapper.valueToTree(body))).result();
            return stored(stored,HttpStatus.ACCEPTED,null);
        } catch (RuntimeException failure) {
            service.recordRescanFailure(actor,attachmentId,body.reason(),key,failure); throw failure;
        }
    }

    private static ResponseEntity<String> stored(StoredCommandResult stored,HttpStatus status,String location) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore()); headers.setETag(stored.etag());
        if(location!=null) headers.setLocation(URI.create(location));
        return new ResponseEntity<>(stored.responseJson(),headers,status);
    }
}
