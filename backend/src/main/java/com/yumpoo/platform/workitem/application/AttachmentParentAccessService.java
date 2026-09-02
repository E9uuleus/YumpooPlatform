package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.ActiveUserSnapshot;
import com.yumpoo.platform.identityaccess.api.ActiveUserSnapshotQuery;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.application.WorkItemModels.WorkItemLocator;
import com.yumpoo.platform.workitem.application.WorkItemUpdateModels.UpdateLocator;
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemUpdate;
import com.yumpoo.platform.workitem.domain.WorkItemUpdateStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
public class AttachmentParentAccessService {
    private final WorkItemRepository workItems;
    private final WorkItemUpdateRepository updates;
    private final ContentRepository contents;
    private final ProjectAccessSnapshotQuery access;
    private final ActiveUserSnapshotQuery users;

    public AttachmentParentAccessService(WorkItemRepository workItems,
            WorkItemUpdateRepository updates, ContentRepository contents,
            ProjectAccessSnapshotQuery access, ActiveUserSnapshotQuery users) {
        this.workItems=workItems; this.updates=updates; this.contents=contents;
        this.access=access; this.users=users;
    }

    @Transactional(readOnly=true)
    public Context require(CurrentActor actor,String ownerType,UUID ownerId,boolean write) {
        return resolve(actor,ownerType,ownerId,write);
    }

    @Transactional(readOnly=true)
    public Context requireOriginal(UUID companyId,UUID uploaderUserId,String ownerType,UUID ownerId) {
        ActiveUserSnapshot user=users.findByUserId(uploaderUserId)
                .filter(value->value.companyId().equals(companyId)&&value.activeAndEnabled())
                .orElseThrow(AttachmentParentAccessService::notWritable);
        try {
            return resolve(new CurrentActor(user.userId(),user.companyId(),user.authorizationVersion(),Set.of()),
                    ownerType,ownerId,true);
        } catch(ApplicationException failure) { throw notWritable(); }
    }

    private Context resolve(CurrentActor actor,String ownerType,UUID ownerId,boolean write) {
        if("WORK_ITEM".equals(ownerType)) {
            WorkItemLocator locator=workItems.findLocator(actor.companyId(),ownerId).orElseThrow(AttachmentParentAccessService::notFound);
            ProjectAccessSnapshot project=visible(actor,locator.projectId());
            Content content=contents.find(project.companyId(),project.projectId(),locator.contentId()).orElseThrow(AttachmentParentAccessService::notFound);
            WorkItem item=workItems.find(project.companyId(),project.projectId(),locator.contentId(),ownerId).orElseThrow(AttachmentParentAccessService::notFound);
            if(write) requireWritable(project,content);
            return new Context(project.companyId(),project.projectId(),item.contentId(),item.id(),null);
        }
        if("WORK_ITEM_UPDATE".equals(ownerType)) {
            UpdateLocator locator=updates.findLocator(actor.companyId(),ownerId).orElseThrow(AttachmentParentAccessService::notFound);
            ProjectAccessSnapshot project=visible(actor,locator.projectId());
            Content content=contents.find(project.companyId(),project.projectId(),locator.contentId()).orElseThrow(AttachmentParentAccessService::notFound);
            workItems.find(project.companyId(),project.projectId(),locator.contentId(),locator.workItemId()).orElseThrow(AttachmentParentAccessService::notFound);
            WorkItemUpdate update=updates.find(project.companyId(),ownerId).orElseThrow(AttachmentParentAccessService::notFound);
            if(update.status()==WorkItemUpdateStatus.DELETED) throw notFound();
            if(write) requireWritable(project,content);
            return new Context(project.companyId(),project.projectId(),locator.contentId(),locator.workItemId(),ownerId);
        }
        throw notFound();
    }

    private ProjectAccessSnapshot visible(CurrentActor actor,UUID projectId) {
        return access.findVisible(actor,projectId).orElseThrow(AttachmentParentAccessService::notFound);
    }
    private static void requireWritable(ProjectAccessSnapshot project,Content content) {
        if(project.actorAccess()==ProjectAccessSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY)
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        if(project.lifecycle()==ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED)
            throw notWritable();
    }
    private static ApplicationException notFound(){return new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);}
    private static ApplicationException notWritable(){return ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,"PARENT_NOT_WRITABLE");}
    public record Context(UUID companyId,UUID projectId,UUID contentId,UUID workItemId,UUID updateId){}
}
