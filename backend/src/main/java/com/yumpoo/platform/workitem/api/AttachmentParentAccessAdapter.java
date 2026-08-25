package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.filestorage.api.AttachmentOwnerType;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.application.AttachmentParentAccessService;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AttachmentParentAccessAdapter implements AttachmentParentAccessPort {
    private final AttachmentParentAccessService service;
    public AttachmentParentAccessAdapter(AttachmentParentAccessService service){this.service=service;}
    public AttachmentParentContext requireWritable(CurrentActor actor,AttachmentOwnerType type,UUID id){return map(service.require(actor,type.name(),id,true));}
    public AttachmentParentContext requireReadable(CurrentActor actor,AttachmentOwnerType type,UUID id){return map(service.require(actor,type.name(),id,false));}
    public AttachmentParentContext requireWritableByOriginalUploader(UUID companyId,UUID userId,AttachmentOwnerType type,UUID id){return map(service.requireOriginal(companyId,userId,type.name(),id));}
    private static AttachmentParentContext map(AttachmentParentAccessService.Context value){return new AttachmentParentContext(value.companyId(),value.projectId(),value.contentId(),value.workItemId(),value.updateId());}
}
