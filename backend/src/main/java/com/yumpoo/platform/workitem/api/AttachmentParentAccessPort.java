package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.filestorage.api.AttachmentOwnerType;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public interface AttachmentParentAccessPort {
    AttachmentParentContext requireWritable(CurrentActor actor, AttachmentOwnerType ownerType,
            UUID ownerId);
    AttachmentParentContext requireReadable(CurrentActor actor, AttachmentOwnerType ownerType,
            UUID ownerId);
    AttachmentParentContext requireWritableByOriginalUploader(UUID companyId, UUID uploaderUserId,
            AttachmentOwnerType ownerType, UUID ownerId);

    record AttachmentParentContext(UUID companyId, UUID projectId, UUID contentId,
            UUID workItemId, UUID updateId) {}
}
