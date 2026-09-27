package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.catalog.api.ProjectActiveMembershipQuery;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.catalog.api.ProjectOwnerScopeQuery;
import com.yumpoo.platform.catalog.api.ProjectSnapshot;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.notification.api.NotificationContextPort;
import com.yumpoo.platform.notification.api.NotificationModels.*;
import com.yumpoo.platform.workitem.api.WorkItemNotificationSourceQuery;
import com.yumpoo.platform.workitem.api.WorkItemReferenceQuery;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class NotificationContextAdapter implements NotificationContextPort {
    private final MinimalUserSnapshotQuery users;
    private final ProjectActiveMembershipQuery memberships;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectOwnerScopeQuery projects;
    private final WorkItemNotificationSourceQuery source;
    private final WorkItemReferenceQuery items;
    public NotificationContextAdapter(MinimalUserSnapshotQuery users,ProjectActiveMembershipQuery memberships,
            ProjectAccessSnapshotQuery access,ProjectOwnerScopeQuery projects,WorkItemNotificationSourceQuery source,WorkItemReferenceQuery items) {
        this.users=users;this.memberships=memberships;this.access=access;this.projects=projects;this.source=source;this.items=items;
    }
    public Optional<Participants> workItemParticipants(UUID company,UUID item) {
        return source.findParticipants(company,item).map(p->new Participants(p.projectId(),p.assigneeUserId(),p.reporterUserId()));
    }
    public Optional<Update> update(UUID company,UUID id) {
        return source.findUpdate(company,id).map(u->new Update(u.projectId(),u.workItemId(),u.authorUserId(),u.parentAuthorUserId()));
    }
    public Optional<UUID> projectOwner(UUID company,UUID project) { return projects.find(company,project).map(ProjectSnapshot::ownerUserId); }
    public Set<UUID> activeAccounts(UUID company,Collection<UUID> ids) {
        if(ids.isEmpty()) return Set.of();
        return users.findByUserIds(company,ids).values().stream().filter(u->u.activeAndEnabled()).map(u->u.userId()).collect(Collectors.toUnmodifiableSet());
    }
    public Set<UUID> eligibleProjectRecipients(UUID company,UUID project,Collection<UUID> ids) {
        return activeAccounts(company,memberships.findActiveMemberIds(company,project,ids));
    }
    public Rendered render(CurrentActor actor,RenderRequest request) {
        var visible=access.findVisible(actor,request.references().stream().map(Reference::projectId).collect(Collectors.toSet())).keySet();
        var projectNames=projects.findAll(actor.companyId(),visible);
        var itemIds=request.references().stream().filter(r->visible.contains(r.projectId())).map(Reference::workItemId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        var updateIds=request.references().stream().filter(r->visible.contains(r.projectId())).map(Reference::updateId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        var references=items.findVisible(actor,itemIds);
        var excerpts=source.findVisibleUpdateExcerpts(actor,updateIds);
        var people=new HashMap<UUID,Person>();
        users.findByUserIds(actor.companyId(),request.userIds()).forEach((id,user)->people.put(id,new Person(id,user.displayName())));
        var targets=new HashMap<UUID,Target>();
        for(var r:request.references()) {
            var project=projectNames.get(r.projectId()); var item=r.workItemId()==null?null:references.get(r.workItemId());
            boolean available=visible.contains(r.projectId()) && project!=null
                    && (r.kind()==TargetKind.PROJECT || (item!=null && item.projectId().equals(r.projectId())))
                    && (r.kind()!=TargetKind.WORK_ITEM_UPDATE || excerpts.containsKey(r.updateId()));
            targets.put(r.id(),available?new Target(r.kind(),true,r.projectId(),project.name(),r.workItemId(),
                    item==null?null:item.itemNo(),item==null?null:item.title(),r.updateId(),r.updateId()==null?null:excerpts.get(r.updateId())):Target.inaccessible(r.kind()));
        }
        return new Rendered(targets,people);
    }
}
