package com.yumpoo.platform.notification.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import com.yumpoo.platform.notification.application.NotificationModels.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class NotificationInboxQueryService {
    private final NotificationRepository repository;
    private final NotificationContext context;
    private final NotificationCursorCodec cursors;
    public NotificationInboxQueryService(NotificationRepository repository,NotificationContext context,NotificationCursorCodec cursors) {
        this.repository=repository;this.context=context;this.cursors=cursors;
    }
    @Transactional(readOnly=true)
    public Page list(CurrentActor actor,ListState state,Group group,String cursor,Integer requestedLimit) {
        int limit=requestedLimit==null?20:requestedLimit;
        if(limit<1 || limit>50) throw new ApplicationException(StandardErrorCode.VALIDATION_FAILED,"limit 必须在 1 到 50 之间");
        ListState effective=state==null?ListState.ALL:state;
        String fingerprint=actor.companyId()+"/"+actor.userId()+"/"+effective+"/"+group;
        Instant now=repository.serverNow();
        var rows=repository.find(actor.companyId(),actor.userId(),effective,group,cursors.decode(cursor,fingerprint),limit+1);
        boolean more=rows.size()>limit;
        rows=rows.subList(0,Math.min(limit,rows.size()));
        var people=new HashSet<UUID>();
        var references=new ArrayList<NotificationContext.Reference>();
        for(var row:rows) {
            var e=row.event();
            if(e.actorUserId()!=null) people.add(e.actorUserId());
            if(e.subjectUserId()!=null) people.add(e.subjectUserId());
            references.add(new NotificationContext.Reference(row.id(),e.kind(),e.projectId(),e.workItemId(),e.updateId()));
        }
        var rendered=context.render(actor,new NotificationContext.RenderRequest(references,people));
        var items=rows.stream().map(row->new Item(row.id(),row.reason(),row.state(),row.createdAt(),row.readAt(),
                row.event().actorUserId()==null?null:rendered.people().get(row.event().actorUserId()),
                row.event().subjectUserId()==null?null:rendered.people().get(row.event().subjectUserId()),
                rendered.targets().getOrDefault(row.id(),Target.inaccessible(row.event().kind())))).toList();
        String next=null;
        if(more) { var last=rows.getLast(); next=cursors.encode(fingerprint,new NotificationRepository.Anchor(last.createdAt(),last.id())); }
        return new Page(items,next,now);
    }
    @Transactional(readOnly=true) public UnreadCounts counts(CurrentActor actor) { return repository.counts(actor.companyId(),actor.userId()); }
    @Transactional public UnreadCounts setState(CurrentActor actor,UUID id,State state) {
        if(!repository.setState(actor.companyId(),actor.userId(),id,state)) throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        return counts(actor);
    }
    @Transactional public UnreadCounts readAll(CurrentActor actor,Instant upTo,Group group) {
        if(upTo==null) throw new ApplicationException(StandardErrorCode.VALIDATION_FAILED,"upTo 不能为空");
        Instant now=repository.serverNow();
        repository.readAll(actor.companyId(),actor.userId(),upTo.isAfter(now)?now:upTo,group);
        return counts(actor);
    }
}
