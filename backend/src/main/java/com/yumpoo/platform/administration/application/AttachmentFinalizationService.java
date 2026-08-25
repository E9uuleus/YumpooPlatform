package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.filestorage.api.AttachmentLifecyclePort;
import com.yumpoo.platform.filestorage.api.AttachmentModels.AttachmentMetadata;
import com.yumpoo.platform.filestorage.api.AttachmentModels.Finalization;
import com.yumpoo.platform.filestorage.api.AttachmentModels.ScanClaim;
import com.yumpoo.platform.filestorage.api.AttachmentModels.ScanOutcome;
import com.yumpoo.platform.filestorage.api.AttachmentRejectedCode;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.workitem.api.AttachmentParentAccessPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.Optional;

@Service
public class AttachmentFinalizationService {
    private final AttachmentLifecyclePort attachments;
    private final AttachmentParentAccessPort parents;
    private final SecurityAuditAppendPort audits;
    private final TransactionalEventPort events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AttachmentFinalizationService(AttachmentLifecyclePort attachments,
            AttachmentParentAccessPort parents, SecurityAuditAppendPort audits,
            TransactionalEventPort events, ObjectMapper objectMapper, Clock clock) {
        this.attachments=attachments; this.parents=parents; this.audits=audits;
        this.events=events; this.objectMapper=objectMapper; this.clock=clock;
    }

    @Transactional
    public void finalizeClean(ScanClaim claim, ScanOutcome.Clean clean) {
        Optional<Finalization> candidate=attachments.prepareFinalization(claim,clean,clock.instant());
        if(candidate.isEmpty()) return;
        Finalization finalization=candidate.orElseThrow();
        try {
            parents.requireWritableByOriginalUploader(finalization.companyId(),
                    finalization.uploadedByUserId(),finalization.ownerType(),finalization.ownerId());
        } catch (RuntimeException failure) {
            attachments.completeRejected(claim,AttachmentRejectedCode.PARENT_NOT_WRITABLE,clock.instant());
            auditRejected(claim,AttachmentRejectedCode.PARENT_NOT_WRITABLE);
            return;
        }
        AttachmentMetadata metadata=attachments.completeAvailable(finalization,clock.instant());
        audits.append(new SecurityAuditDraft(metadata.companyId(),"attachment-available:"+metadata.id(),
                "ATTACHMENT_AVAILABLE",SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.system("ATTACHMENT_SCANNER"),"ATTACHMENT",metadata.id().toString(),
                null,null,objectMapper.valueToTree(metadata),null,null,null,null,clock.instant()));
        ObjectNode payload=objectMapper.createObjectNode();
        payload.put("attachmentId",metadata.id().toString());
        payload.put("ownerType",metadata.ownerType().name());
        payload.put("ownerId",metadata.ownerId().toString());
        payload.put("projectId",metadata.projectId().toString());
        payload.put("fileName",metadata.originalFileName());
        payload.put("detectedMime",metadata.detectedMime());
        payload.put("sizeBytes",metadata.sizeBytes());
        payload.put("uploadedByUserId",metadata.uploadedByUserId().toString());
        payload.put("rowVersion",metadata.rowVersion());
        events.append(new EventDraft("filestorage.attachment_available",1,"Attachment",metadata.id(),
                metadata.rowVersion(),metadata.companyId(),EventActor.system("ATTACHMENT_SCANNER"),payload));
    }

    @Transactional
    public void finalizeRejected(ScanClaim claim, AttachmentRejectedCode code) {
        attachments.completeRejected(claim,code,clock.instant());
        auditRejected(claim,code);
    }

    private void auditRejected(ScanClaim claim, AttachmentRejectedCode code) {
        ObjectNode after=objectMapper.createObjectNode();
        after.put("rejectedCode",code.name());
        audits.append(new SecurityAuditDraft(claim.companyId(),"attachment-rejected:"+claim.attachmentId()
                +":"+claim.generation(),"ATTACHMENT_REJECTED",SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.system("ATTACHMENT_SCANNER"),"ATTACHMENT",
                claim.attachmentId().toString(),null,null,after,null,null,null,null,clock.instant()));
    }
}
