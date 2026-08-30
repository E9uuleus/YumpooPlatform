package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.filestorage.api.AttachmentLifecyclePort;
import com.yumpoo.platform.filestorage.api.AttachmentModels.AttachmentIntentResult;
import com.yumpoo.platform.filestorage.api.AttachmentModels.AttachmentMetadata;
import com.yumpoo.platform.filestorage.api.AttachmentModels.AttachmentPage;
import com.yumpoo.platform.filestorage.api.AttachmentModels.CreateIntent;
import com.yumpoo.platform.filestorage.api.AttachmentModels.RescanResult;
import com.yumpoo.platform.filestorage.api.AttachmentModels.UploadContent;
import com.yumpoo.platform.filestorage.api.AttachmentModels.AttachmentCapabilities;
import com.yumpoo.platform.filestorage.api.AttachmentModels.DeleteResult;
import com.yumpoo.platform.filestorage.api.AttachmentModels.DownloadContent;
import com.yumpoo.platform.filestorage.api.AttachmentStatus;
import com.yumpoo.platform.filestorage.api.AttachmentOwnerType;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyCommand;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyScope;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.workitem.api.AttachmentParentAccessPort;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AttachmentApplicationService {
    private final AttachmentLifecyclePort attachments;
    private final AttachmentParentAccessPort parents;
    private final IdempotentCommandExecutor idempotency;
    private final SecurityAuditAppendPort audits;
    private final TransactionalEventPort events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AttachmentApplicationService(AttachmentLifecyclePort attachments,
            AttachmentParentAccessPort parents, IdempotentCommandExecutor idempotency,
            SecurityAuditAppendPort audits, TransactionalEventPort events,
            ObjectMapper objectMapper, Clock clock) {
        this.attachments = attachments;
        this.parents = parents;
        this.idempotency = idempotency;
        this.audits = audits;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public IdempotencyExecutionResult create(CurrentActor actor, AttachmentIntentCommand request,
            UUID key, RequestHash hash) {
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(actor.userId(),
                "POST", "createAttachmentIntent", key), hash), () -> {
            if (request.ownerType() == AttachmentOwnerType.PRODUCT_FEEDBACK
                    || request.ownerType() == AttachmentOwnerType.FEEDBACK_UPDATE) {
                throw ApplicationException.validation(new FieldViolation("ownerType",
                        "OWNER_TYPE_NOT_AVAILABLE", "该附件归属类型将在后续里程碑开放"));
            }
            AttachmentParentAccessPort.AttachmentParentContext parent =
                    parents.requireWritable(actor, request.ownerType(), request.ownerId());
            AttachmentIntentResult result = attachments.createIntent(new CreateIntent(UUID.randomUUID(),
                    parent.companyId(), parent.projectId(), request.ownerType(), request.ownerId(),
                    request.originalFileName(), request.declaredMime(), request.sizeBytes(),
                    actor.userId(), clock.instant()));
            return stored(201, result, result.metadata().id(), result.metadata().etag());
        });
    }

    public AttachmentMetadata find(CurrentActor actor, UUID attachmentId) {
        AttachmentMetadata metadata = attachments.find(actor.companyId(), attachmentId, clock.instant())
                .orElseThrow(AttachmentApplicationService::notFound);
        if (metadata.status() == AttachmentStatus.DELETED) throw notFound();
        parents.requireReadable(actor, metadata.ownerType(), metadata.ownerId());
        return capabilities(actor, metadata);
    }

    public AttachmentPage list(CurrentActor actor, AttachmentOwnerType ownerType, UUID ownerId,
            String cursor, Integer size) {
        parents.requireReadable(actor, ownerType, ownerId);
        AttachmentPage page=attachments.list(actor.companyId(), ownerType, ownerId, cursor,
                size == null ? 20 : size, clock.instant());
        List<AttachmentMetadata> items=page.items().stream().map(value->capabilities(actor,value)).toList();
        return new AttachmentPage(items,page.nextCursor());
    }

    public AttachmentMetadata upload(CurrentActor actor, UUID attachmentId, InputStream content,
            OptionalLong contentLength) {
        AttachmentMetadata current = find(actor, attachmentId);
        if (!current.uploadedByUserId().equals(actor.userId())) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        parents.requireWritable(actor, current.ownerType(), current.ownerId());
        return attachments.upload(new UploadContent(actor.companyId(), attachmentId, content,
                contentLength, clock.instant()));
    }

    public DownloadContent download(CurrentActor actor,UUID attachmentId) {
        AttachmentMetadata current=attachments.find(actor.companyId(),attachmentId,clock.instant())
                .filter(value->value.status()==AttachmentStatus.AVAILABLE)
                .orElseThrow(AttachmentApplicationService::notFound);
        parents.requireReadable(actor,current.ownerType(),current.ownerId());
        return attachments.download(actor.companyId(),attachmentId,clock.instant());
    }

    public AttachmentMetadata requireDeleteTarget(CurrentActor actor,UUID attachmentId) {
        AttachmentMetadata current=attachments.find(actor.companyId(),attachmentId,clock.instant())
                .orElseThrow(AttachmentApplicationService::notFound);
        parents.requireWritable(actor,current.ownerType(),current.ownerId());
        return current;
    }

    public IdempotencyExecutionResult delete(CurrentActor actor,UUID attachmentId,long expectedVersion,
            String reason,UUID key,RequestHash hash) {
        AttachmentMetadata visible=attachments.find(actor.companyId(),attachmentId,clock.instant())
                .orElseThrow(AttachmentApplicationService::notFound);
        AttachmentParentAccessPort.AttachmentParentContext parent =
                parents.requireWritable(actor,visible.ownerType(),visible.ownerId());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(actor.userId(),
                "DELETE","deleteAttachment",key),hash),()->{
            DeleteResult result=attachments.delete(actor.companyId(),attachmentId,actor.userId(),
                    reason,expectedVersion,clock.instant());
            audits.append(new SecurityAuditDraft(actor.companyId(),"attachment-delete:"+key,
                    "ATTACHMENT_DELETED",SecurityAuditOutcome.SUCCEEDED,
                    SecurityAuditActor.user(actor.userId(),Set.of()),"ATTACHMENT",attachmentId.toString(),
                    result.deleteReason(),objectMapper.valueToTree(visible),objectMapper.valueToTree(result),
                    null,key,null,null,clock.instant()));
            ObjectNode payload=objectMapper.createObjectNode();
            payload.put("attachmentId",attachmentId.toString());
            payload.put("ownerType",visible.ownerType().name());
            payload.put("ownerId",visible.ownerId().toString());
            payload.put("projectId",visible.projectId().toString());
            payload.put("contentId",parent.contentId().toString());
            payload.put("workItemId",parent.workItemId().toString());
            payload.put("fileName",visible.originalFileName());
            payload.put("sizeBytes",visible.sizeBytes());
            payload.put("deletedByUserId",result.deletedByUserId().toString());
            payload.put("deleteReason",result.deleteReason());
            payload.put("deletedAt",result.deletedAt().toString());
            payload.put("previousRowVersion",result.previousRowVersion());
            payload.put("rowVersion",result.rowVersion());
            events.append(new EventDraft("filestorage.attachment_deleted",1,"Attachment",attachmentId,
                    result.rowVersion(),actor.companyId(),EventActor.user(actor.userId()),payload));
            return stored(200,result,attachmentId,result.etag());
        });
    }

    public AttachmentMetadata requireRescanTarget(CurrentActor actor, UUID attachmentId) {
        requireAppManager(actor);
        return attachments.find(actor.companyId(), attachmentId, clock.instant())
                .orElseThrow(AttachmentApplicationService::notFound);
    }

    public IdempotencyExecutionResult rescan(CurrentActor actor, UUID attachmentId,
            long expectedVersion, String reason, UUID key, RequestHash hash) {
        requireAppManager(actor);
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(actor.userId(),
                "POST", "rescanAttachment", key), hash), () -> {
            AttachmentMetadata current = requireRescanTarget(actor, attachmentId);
            parents.requireWritableByOriginalUploader(current.companyId(), current.uploadedByUserId(),
                    current.ownerType(), current.ownerId());
            RescanResult result = attachments.rescan(actor.companyId(), attachmentId,
                    expectedVersion, clock.instant());
            audits.append(new SecurityAuditDraft(actor.companyId(), "attachment-rescan:" + key,
                    "ATTACHMENT_RESCAN", SecurityAuditOutcome.SUCCEEDED,
                    SecurityAuditActor.user(actor.userId(), Set.of("APP_MANAGER")), "ATTACHMENT",
                    attachmentId.toString(), reason.strip(), null, objectMapper.valueToTree(result),
                    null, key, null, null, clock.instant()));
            return stored(202, result, attachmentId, result.etag());
        });
    }

    public void recordRescanFailure(CurrentActor actor, UUID attachmentId, String reason,
            UUID key, RuntimeException failure) {
        if (!actor.hasRole(PlatformRoleCode.APP_MANAGER)) return;
        String error = failure instanceof ApplicationException application
                ? application.errorCode().name() : StandardErrorCode.INTERNAL_ERROR.name();
        audits.appendIndependent(new SecurityAuditDraft(actor.companyId(), "attachment-rescan-failed:" + key,
                "ATTACHMENT_RESCAN", SecurityAuditOutcome.FAILED,
                SecurityAuditActor.user(actor.userId(), Set.of("APP_MANAGER")), "ATTACHMENT",
                attachmentId.toString(), reason == null || reason.isBlank() ? "unspecified" : reason.strip(),
                null, null, error, key, null, null, clock.instant()));
    }

    private StoredCommandResult stored(int status, Object body, UUID id, String etag) {
        try {
            return new StoredCommandResult(status, objectMapper.writeValueAsString(body), id, etag);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("attachment response serialization failed", exception);
        }
    }

    private static void requireAppManager(CurrentActor actor) {
        if (!actor.hasRole(PlatformRoleCode.APP_MANAGER)) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
    }
    private AttachmentMetadata capabilities(CurrentActor actor,AttachmentMetadata value) {
        boolean writable;
        try {
            parents.requireWritable(actor,value.ownerType(),value.ownerId());
            writable=true;
        } catch(ApplicationException failure) {
            writable=false;
        }
        boolean available=value.status()==AttachmentStatus.AVAILABLE;
        boolean canUpload=value.capabilities().canUploadContent()&&writable
                &&value.uploadedByUserId().equals(actor.userId());
        return new AttachmentMetadata(value.id(),value.companyId(),value.projectId(),value.ownerType(),
                value.ownerId(),value.originalFileName(),value.declaredMime(),value.detectedMime(),
                value.sizeBytes(),value.status(),value.rejectedCode(),value.uploadedByUserId(),
                value.createdAt(),value.availableAt(),value.expiresAt(),value.rowVersion(),value.etag(),
                new AttachmentCapabilities(canUpload,available,available&&writable));
    }
    private static ApplicationException notFound() {
        return new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
    }
}
