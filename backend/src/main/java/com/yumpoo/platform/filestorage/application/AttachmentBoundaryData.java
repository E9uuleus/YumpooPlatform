package com.yumpoo.platform.filestorage.application;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

public final class AttachmentBoundaryData {
    private AttachmentBoundaryData() {}
    public record Record(UUID id,UUID companyId,UUID projectId,String ownerType,UUID ownerId,
            String originalFileName,String declaredMime,String detectedMime,Long sizeBytes,
            String status,String rejectedCode,UUID uploadedByUserId,Instant createdAt,
            Instant availableAt,Instant expiresAt,long rowVersion,boolean canUploadContent){}
    public record Create(UUID id,UUID companyId,UUID projectId,String ownerType,UUID ownerId,
            String originalFileName,String declaredMime,Long sizeBytes,UUID uploadedByUserId,Instant now){}
    public record Upload(UUID companyId,UUID attachmentId,InputStream content,OptionalLong contentLength,Instant now){}
    public record Intent(String uploadUrl,Instant expiresAt,long maxBytes,Record metadata){}
    public record Page(List<Record> items,String nextCursor){}
    public record Claim(UUID taskId,UUID leaseToken,UUID attachmentId,UUID companyId,UUID projectId,
            String ownerType,UUID ownerId,UUID uploadedByUserId,String originalFileName,String declaredMime,
            long sizeBytes,String sha256,String detectedMime,String storageKey,int generation,int attemptCount){}
    public sealed interface Outcome {
        record Clean(String detectedMime,String storageKey) implements Outcome{}
        record Rejected(String code) implements Outcome{}
        record Unavailable() implements Outcome{}
    }
    public record Finalize(UUID attachmentId,UUID companyId,UUID projectId,String ownerType,
            UUID ownerId,UUID uploadedByUserId,String originalFileName,String detectedMime,long sizeBytes,
            String storageKey,int generation,UUID taskId,UUID leaseToken){}
    public record Rescan(UUID attachmentId,String status,int generation,long rowVersion,String etag){}
}
