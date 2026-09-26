package com.yumpoo.platform.notification.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.notification.application.NotificationContext;
import com.yumpoo.platform.notification.application.NotificationModels;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class NotificationContextBridge implements NotificationContext {
    private final NotificationContextPort context;
    public NotificationContextBridge(NotificationContextPort context) { this.context=context; }
    public Optional<Participants> workItemParticipants(UUID company,UUID item) {
        return context.workItemParticipants(company,item).map(p->new Participants(p.projectId(),p.assigneeUserId(),p.reporterUserId()));
    }
    public Optional<Update> update(UUID company,UUID update) {
        return context.update(company,update).map(p->new Update(p.projectId(),p.workItemId(),p.authorUserId(),p.parentAuthorUserId()));
    }
    public Optional<UUID> projectOwner(UUID company,UUID project) { return context.projectOwner(company,project); }
    public Set<UUID> eligibleProjectRecipients(UUID company,UUID project,Collection<UUID> users) { return context.eligibleProjectRecipients(company,project,users); }
    public Set<UUID> activeAccounts(UUID company,Collection<UUID> users) { return context.activeAccounts(company,users); }
    public Rendered render(CurrentActor actor,RenderRequest request) {
        var rendered=context.render(actor,new NotificationContextPort.RenderRequest(request.references().stream().map(r->
                new NotificationContextPort.Reference(r.id(),com.yumpoo.platform.notification.api.NotificationModels.TargetKind.valueOf(r.kind().name()),
                        r.projectId(),r.workItemId(),r.updateId())).toList(),request.userIds()));
        var targets=new HashMap<UUID,NotificationModels.Target>();
        rendered.targets().forEach((id,t)->targets.put(id,new NotificationModels.Target(NotificationModels.TargetKind.valueOf(t.kind().name()),
                t.accessible(),t.projectId(),t.projectName(),t.workItemId(),t.itemNo(),t.title(),t.updateId(),t.excerpt())));
        var people=new HashMap<UUID,NotificationModels.Person>();
        rendered.people().forEach((id,p)->people.put(id,new NotificationModels.Person(p.id(),p.displayName())));
        return new Rendered(targets,people);
    }
}
