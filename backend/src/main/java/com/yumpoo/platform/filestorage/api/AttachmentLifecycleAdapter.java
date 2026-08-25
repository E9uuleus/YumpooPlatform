package com.yumpoo.platform.filestorage.api;

import com.yumpoo.platform.filestorage.api.AttachmentModels.*;
import com.yumpoo.platform.filestorage.application.AttachmentBoundaryData;
import com.yumpoo.platform.filestorage.application.AttachmentLifecycleService;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class AttachmentLifecycleAdapter implements AttachmentLifecyclePort {
    private final AttachmentLifecycleService service;
    public AttachmentLifecycleAdapter(AttachmentLifecycleService service){this.service=service;}

    public AttachmentIntentResult createIntent(CreateIntent value){
        AttachmentBoundaryData.Intent result=service.createBoundary(new AttachmentBoundaryData.Create(
                value.id(),value.companyId(),value.projectId(),value.ownerType().name(),value.ownerId(),
                value.originalFileName(),value.declaredMime(),value.sizeBytes(),value.uploadedByUserId(),value.now()));
        return new AttachmentIntentResult(result.uploadUrl(),result.expiresAt(),result.maxBytes(),map(result.metadata()));
    }
    public AttachmentMetadata upload(UploadContent value){return map(service.uploadBoundary(
            new AttachmentBoundaryData.Upload(value.companyId(),value.attachmentId(),value.content(),value.contentLength(),value.now())));}
    public Optional<AttachmentMetadata> find(UUID companyId,UUID id,Instant now){return service.findBoundary(companyId,id,now).map(AttachmentLifecycleAdapter::map);}
    public AttachmentPage list(UUID companyId,AttachmentOwnerType type,UUID ownerId,String cursor,int size,Instant now){
        AttachmentBoundaryData.Page page=service.listBoundary(companyId,type.name(),ownerId,cursor,size,now);
        return new AttachmentPage(page.items().stream().map(AttachmentLifecycleAdapter::map).toList(),page.nextCursor());
    }
    public Optional<ScanClaim> claimDue(String workerId,Instant now){return service.claimBoundary(workerId,now).map(AttachmentLifecycleAdapter::map);}
    public ScanOutcome scan(ScanClaim value){
        AttachmentBoundaryData.Outcome outcome=service.scanBoundary(internal(value));
        if(outcome instanceof AttachmentBoundaryData.Outcome.Clean clean)return new ScanOutcome.Clean(clean.detectedMime(),clean.storageKey());
        if(outcome instanceof AttachmentBoundaryData.Outcome.Rejected rejected)return new ScanOutcome.Rejected(AttachmentRejectedCode.valueOf(rejected.code()));
        return new ScanOutcome.Unavailable();
    }
    public Optional<Finalization> prepareFinalization(ScanClaim claim,ScanOutcome.Clean clean,Instant now){
        return service.prepareBoundary(internal(claim),new AttachmentBoundaryData.Outcome.Clean(clean.detectedMime(),clean.storageKey()),now)
                .map(AttachmentLifecycleAdapter::map);
    }
    public AttachmentMetadata completeAvailable(Finalization value,Instant now){return map(service.completeAvailableBoundary(internal(value),now));}
    public void completeRejected(ScanClaim claim,AttachmentRejectedCode code,Instant now){service.completeRejectedBoundary(internal(claim),code.name(),now);}
    public void retryOrExhaust(ScanClaim claim,Instant now){service.retryBoundary(internal(claim),now);}
    public RescanResult rescan(UUID companyId,UUID id,long version,Instant now){
        AttachmentBoundaryData.Rescan result=service.rescanBoundary(companyId,id,version,now);
        return new RescanResult(result.attachmentId(),AttachmentStatus.valueOf(result.status()),result.generation(),result.rowVersion(),result.etag());
    }

    private static AttachmentMetadata map(AttachmentBoundaryData.Record value){
        return new AttachmentMetadata(value.id(),value.companyId(),value.projectId(),AttachmentOwnerType.valueOf(value.ownerType()),
                value.ownerId(),value.originalFileName(),value.declaredMime(),value.detectedMime(),value.sizeBytes(),
                AttachmentStatus.valueOf(value.status()),value.rejectedCode()==null?null:AttachmentRejectedCode.valueOf(value.rejectedCode()),
                value.uploadedByUserId(),value.createdAt(),value.availableAt(),value.expiresAt(),value.rowVersion(),
                StrongEtag.format(value.rowVersion()),new AttachmentCapabilities(value.canUploadContent()));
    }
    private static ScanClaim map(AttachmentBoundaryData.Claim value){return new ScanClaim(value.taskId(),value.leaseToken(),value.attachmentId(),value.companyId(),value.projectId(),AttachmentOwnerType.valueOf(value.ownerType()),value.ownerId(),value.uploadedByUserId(),value.originalFileName(),value.declaredMime(),value.sizeBytes(),value.sha256(),value.detectedMime(),value.storageKey(),value.generation(),value.attemptCount());}
    private static AttachmentBoundaryData.Claim internal(ScanClaim value){return new AttachmentBoundaryData.Claim(value.taskId(),value.leaseToken(),value.attachmentId(),value.companyId(),value.projectId(),value.ownerType().name(),value.ownerId(),value.uploadedByUserId(),value.originalFileName(),value.declaredMime(),value.sizeBytes(),value.sha256(),value.detectedMime(),value.storageKey(),value.generation(),value.attemptCount());}
    private static Finalization map(AttachmentBoundaryData.Finalize value){return new Finalization(value.attachmentId(),value.companyId(),value.projectId(),AttachmentOwnerType.valueOf(value.ownerType()),value.ownerId(),value.uploadedByUserId(),value.originalFileName(),value.detectedMime(),value.sizeBytes(),value.storageKey(),value.generation(),value.taskId(),value.leaseToken());}
    private static AttachmentBoundaryData.Finalize internal(Finalization value){return new AttachmentBoundaryData.Finalize(value.attachmentId(),value.companyId(),value.projectId(),value.ownerType().name(),value.ownerId(),value.uploadedByUserId(),value.originalFileName(),value.detectedMime(),value.sizeBytes(),value.storageKey(),value.generation(),value.taskId(),value.leaseToken());}
}
