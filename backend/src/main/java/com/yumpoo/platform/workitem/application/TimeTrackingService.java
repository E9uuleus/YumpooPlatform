package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.*;
import com.yumpoo.platform.identityaccess.api.*;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.*;
import com.yumpoo.platform.foundation.application.event.*;
import com.yumpoo.platform.foundation.application.idempotency.*;
import com.yumpoo.platform.audit.api.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.yumpoo.platform.workitem.application.TimeTrackingModels.*;

@Service
public class TimeTrackingService {
    public record Input(UUID workItemId, UUID sessionId, Instant startedAt, Instant stoppedAt, String reason) {}
    private final TimeTrackingRepository timers;
    private final TimeShareMaintainer shares;
    private final WorkItemRepository items;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectFactWriteGuard guard;
    private final ProjectDeletionQuery deletion;
    private final MinimalUserSnapshotQuery users;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final SecurityAuditAppendPort audits;
    private final ObjectMapper json;
    private final Clock clock;

    public TimeTrackingService(TimeTrackingRepository timers, TimeShareMaintainer shares, WorkItemRepository items,
            ProjectAccessSnapshotQuery access, ProjectFactWriteGuard guard, ProjectDeletionQuery deletion, MinimalUserSnapshotQuery users,
            IdempotentCommandExecutor idempotency, TransactionalEventPort events, SecurityAuditAppendPort audits,
            ObjectMapper json, Clock clock) {
        this.timers=timers; this.shares=shares; this.items=items; this.access=access; this.guard=guard; this.deletion=deletion; this.users=users;
        this.idempotency=idempotency; this.events=events; this.audits=audits; this.json=json; this.clock=clock;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public CurrentTimeTracker current(CurrentActor actor) {
        return currentView(actor);
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public TimerCandidatePage candidates(CurrentActor actor, String query, String scope, int offset, int limit) {
        String normalized=query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if(normalized.length()>200) throw validation("q", "INVALID_LENGTH", "搜索词最多 200 字");
        if(!Set.of("PERSONAL", "ALL").contains(scope)) throw validation("scope", "INVALID_VALUE", "无效的工作项范围");
        if(offset<0 || offset>100000 || limit<1 || limit>50) throw validation("offset", "INVALID_PAGE", "无效的分页参数");
        var projects=access.findWritableProjects(actor);
        if(projects.isEmpty()) return new TimerCandidatePage(List.of(), null);
        var names=projects.stream().collect(java.util.stream.Collectors.toMap(
                ProjectAccessSnapshotQuery.WritableProject::projectId, ProjectAccessSnapshotQuery.WritableProject::name));
        var matching=projects.stream().filter(p -> !normalized.isEmpty()
                && (p.name().toLowerCase(Locale.ROOT).contains(normalized) || p.code().toLowerCase(Locale.ROOT).contains(normalized)))
                .map(ProjectAccessSnapshotQuery.WritableProject::projectId).toList();
        var rows=timers.candidates(actor.companyId(), actor.userId(), names.keySet(), matching, normalized,
                scope.equals("PERSONAL"), offset, limit+1, now());
        return new TimerCandidatePage(rows.stream().limit(limit).map(row -> row.withProjectName(names.get(row.projectId()))).toList(),
                rows.size()>limit ? offset+limit : null);
    }

    private CurrentTimeTracker currentView(CurrentActor actor) {
        long version=timers.stateVersion(actor.companyId(),actor.userId(),false);
        Instant now=now();
        Session running=timers.running(actor.companyId(),actor.userId()).orElse(null);
        var recent=timers.recentItems(actor.companyId(),actor.userId());
        var visibleProjects=access.findVisible(actor,recent.stream().map(RecentTimeTrackingItem::projectId).distinct().toList());
        var recentItems=recent.stream().filter(item -> visibleProjects.containsKey(item.projectId())).limit(5).toList();
        if(running==null) return CurrentTimeTracker.of(null,null,version,now,recentItems);
        var project=access.findVisible(actor,running.projectId());
        var locator=project.isPresent() ? items.findLocator(actor.companyId(),running.workItemId()) : Optional.<WorkItemModels.WorkItemLocator>empty();
        String title=locator.flatMap(l -> items.find(actor.companyId(),l.projectId(),l.contentId(),l.workItemId())).map(i -> i.title()).orElse(null);
        return CurrentTimeTracker.of(view(running,actor,false,now,title!=null),title,version,now,recentItems);
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public SummaryPage summaries(CurrentActor actor, UUID projectId, Collection<UUID> ids) {
        visible(actor,projectId);
        if(ids==null || ids.size()>100) throw validation("workItemId","INVALID_SIZE","一次最多查询 100 个工作项");
        List<UUID> visibleIds=timers.visibleItemIds(actor.companyId(),projectId,ids);
        Instant now=now();
        return new SummaryPage(timers.summaries(actor.companyId(),projectId,actor.userId(),visibleIds,now),now,timers.revision(actor.companyId(),projectId));
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public SessionPage history(CurrentActor actor, UUID itemId, String cursor, boolean includeChildren) {
        var locator=locator(actor,itemId);
        var project=visible(actor,locator.projectId());
        Instant before=null; UUID beforeId=null;
        if(cursor!=null) try {
            if(cursor.length()>256) throw new IllegalArgumentException();
            String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=3 || !parts[0].equals(itemId.toString())) throw new IllegalArgumentException();
            before=Instant.parse(parts[1]); beforeId=UUID.fromString(parts[2]);
        } catch(RuntimeException e) { throw validation("cursor","INVALID_CURSOR","计时记录游标无效"); }
        List<Session> rows=timers.history(actor.companyId(),itemId,includeChildren,before,beforeId,51);
        String next=null;
        if(rows.size()>50) {
            rows=rows.subList(0,50); Session last=rows.getLast();
            next=Base64.getUrlEncoder().withoutPadding().encodeToString((itemId+"|"+last.startedAt()+"|"+last.id()).getBytes(StandardCharsets.UTF_8));
        }
        boolean writable=canWrite(project) && items.find(actor.companyId(),locator.projectId(),locator.contentId(),itemId)
                .filter(item -> !item.archived()).isPresent(); Instant now=now();
        var names=users.findByUserIds(actor.companyId(),rows.stream().map(Session::userId).collect(java.util.stream.Collectors.toSet()));
        var allocated=timers.sessionShares(actor.companyId(),rows.stream().map(Session::id).toList(),now);
        var sessionItems=items.findByIds(actor.companyId(),locator.projectId(),rows.stream().map(Session::workItemId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(com.yumpoo.platform.workitem.domain.WorkItem::id,item -> item));
        return new SessionPage(rows.stream().map(s -> {
            var item=sessionItems.get(s.workItemId());
            boolean child=!s.workItemId().equals(itemId);
            return view(s,actor,canWrite(project) && item!=null && !item.archived(),now,true,names,allocated,
                    child && item!=null ? item.itemNo() : null,child && item!=null ? item.title() : null);
        }).toList(),next,
                timers.summaries(actor.companyId(),locator.projectId(),actor.userId(),List.of(itemId),now).getFirst(),now,writable);
    }

    public StoredCommandResult command(CurrentActor actor, String action, Input input, long expectedVersion,
            UUID key, RequestHash hash) {
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(actor.userId(),
                "POST","timeTracking:"+action,key),hash), () -> mutate(actor,action,input,expectedVersion)).result();
    }

    private StoredCommandResult mutate(CurrentActor actor, String action, Input input, long expectedVersion) {
        boolean timerCommand=Set.of("start","switch","stop").contains(action);
        WorkItemModels.WorkItemLocator target=action.equals("stop") ? null : locator(actor,input.workItemId());
        Session observedRunning=timerCommand ? timers.running(actor.companyId(),actor.userId()).orElse(null) : null;
        if(timerCommand) {
            Set<UUID> projects=new TreeSet<>();
            if(target!=null) projects.add(target.projectId());
            if(observedRunning!=null) projects.add(observedRunning.projectId());
            for(UUID projectId:projects) {
                deletion.lockForProjection(actor.companyId(),projectId).filter(project -> project.purgeStartedAt()==null)
                        .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            }
        }
        if(target!=null) {
            var project=guard.lockForFactWrite(actor,target.projectId());
            if(project.actorAccess()==ProjectFactWriteSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY)
                throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
            if(project.lifecycle()==ProjectFactWriteSnapshot.ProjectLifecycle.ARCHIVED) throw invalid("PROJECT_ARCHIVED");
        }
        long stateVersion=timers.stateVersion(actor.companyId(),actor.userId(),true);
        if(target!=null) items.lockProjectItem(actor.companyId(),target.projectId(),target.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        Instant now=now(); Set<UUID> changedProjects=new HashSet<>();
        Object result; String etag; UUID resourceId;
        if(timerCommand) {
            requireVersion(stateVersion,expectedVersion);
            Session running=timers.running(actor.companyId(),actor.userId()).orElse(null);
            if(action.equals("start") && running!=null) throw invalid("TIMER_ALREADY_RUNNING");
            if(!action.equals("start") && (running==null || !running.id().equals(input.sessionId()))) throw invalid("TIMER_CHANGED");
            // A changed session would require taking another project lock after timer_state.
            if(running!=null && (observedRunning==null || !running.id().equals(observedRunning.id()))) throw invalid("TIMER_CHANGED");
            if(running!=null) {
                Session stopped=new Session(running.id(),running.companyId(),running.projectId(),running.workItemId(),running.userId(),
                        running.startedAt(),now.isBefore(running.startedAt()) ? running.startedAt() : now,running.source(),running.rowVersion()+1,null,null);
                timers.save(stopped); publish(actor,"stopped",running,stopped,null,now); changedProjects.add(stopped.projectId());
            }
            if(!action.equals("stop")) {
                Session created=new Session(UUID.randomUUID(),actor.companyId(),target.projectId(),target.workItemId(),actor.userId(),now,null,"TIMER",0,null,null);
                timers.save(created); publish(actor,"started",null,created,null,now); changedProjects.add(created.projectId());
            }
            rebuild(actor.companyId(),actor.userId(),now,null,changedProjects);
            timers.advanceState(actor.companyId(),actor.userId());
            CurrentTimeTracker current=currentView(actor); result=current; etag=current.etag(); resourceId=actor.userId();
        } else {
            boolean create=action.equals("create"); boolean delete=action.equals("delete");
            Session before=create ? null : timers.find(actor.companyId(),input.sessionId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            if(before!=null) {
                if(!before.workItemId().equals(input.workItemId())) throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
                if(!before.userId().equals(actor.userId())) throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
                requireVersion(before.rowVersion(),expectedVersion);
                if(before.stoppedAt()==null || before.deletedAt()!=null) throw invalid("SESSION_NOT_EDITABLE");
            }
            String reason=input.reason()==null || input.reason().isBlank() ? null : input.reason().strip();
            if(reason!=null && reason.length()>500)
                throw validation("reason","INVALID_LENGTH","原因最多 500 字");
            Instant start=delete ? before.startedAt() : input.startedAt();
            Instant stop=delete ? before.stoppedAt() : input.stoppedAt();
            if(start!=null) start=start.truncatedTo(ChronoUnit.MILLIS);
            if(stop!=null) stop=stop.truncatedTo(ChronoUnit.MILLIS);
            if(!delete && (start==null || stop==null || !stop.isAfter(start) || stop.isAfter(now)))
                throw validation("stoppedAt","INVALID_RANGE","结束时间须晚于开始时间且不能在未来");
            UUID id=create ? UUID.randomUUID() : before.id();
            if(!delete && timers.overlaps(actor.companyId(),actor.userId(),start,stop,id))
                throw validation("startedAt","TIME_OVERLAP","与本人的其他计时记录重叠");
            Session after=new Session(id,actor.companyId(),target.projectId(),target.workItemId(),actor.userId(),
                    start.truncatedTo(ChronoUnit.MILLIS),stop.truncatedTo(ChronoUnit.MILLIS),create ? "MANUAL" : before.source(),
                    create ? 0 : before.rowVersion()+1,delete ? now : null,reason);
            timers.save(after);
            Instant from=before==null || after.startedAt().isBefore(before.startedAt()) ? after.startedAt() : before.startedAt();
            Instant to=before==null || after.stoppedAt().isAfter(before.stoppedAt()) ? after.stoppedAt() : before.stoppedAt();
            rebuild(actor.companyId(),actor.userId(),from,to,Set.of(after.projectId()));
            publish(actor,create ? "added" : delete ? "deleted" : "edited",before,after,reason,now);
            var v=view(after,actor,true,now,true); result=v; etag=v.etag(); resourceId=id;
        }
        return new StoredCommandResult(200,json.writeValueAsString(result),resourceId,etag);
    }

    /** Runs inside the archive transaction after the project row lock, matching the project-then-timer_state order of timer commands. */
    @Transactional(propagation=Propagation.MANDATORY)
    public int stopRunningInArchivedProject(CurrentActor actor, UUID projectId) {
        Instant now=now(); int stoppedCount=0;
        for(UUID userId:timers.runningUserIds(actor.companyId(),projectId)) {
            timers.stateVersion(actor.companyId(),userId,true);
            Session running=timers.running(actor.companyId(),userId).filter(s -> s.projectId().equals(projectId)).orElse(null);
            if(running==null) continue;
            Session stopped=new Session(running.id(),running.companyId(),running.projectId(),running.workItemId(),running.userId(),
                    running.startedAt(),now.isBefore(running.startedAt()) ? running.startedAt() : now,running.source(),running.rowVersion()+1,null,null);
            timers.save(stopped); publish(actor,"stopped",running,stopped,null,now);
            var affected=shares.rebuild(actor.companyId(),userId,now,null);
            affected.remove(projectId);
            timers.touchProjects(actor.companyId(),affected);
            timers.advanceState(actor.companyId(),userId);
            stoppedCount++;
        }
        if(stoppedCount>0) timers.advanceProjects(actor.companyId(),List.of(projectId));
        return stoppedCount;
    }

    private void rebuild(UUID companyId,UUID userId,Instant from,Instant to,Set<UUID> written) {
        var affected=shares.rebuild(companyId,userId,from,to);
        timers.advanceProjects(companyId,written);
        affected.removeAll(written);
        timers.touchProjects(companyId,affected);
    }

    private void publish(CurrentActor actor, String action, Session before, Session after, String reason, Instant now) {
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("projectId",after.projectId()); payload.put("workItemId",after.workItemId());
        payload.put("sessionId",after.id()); payload.put("userId",after.userId());
        payload.put("startedAt",after.startedAt()); payload.put("stoppedAt",after.stoppedAt());
        payload.put("source",after.source()); payload.put("rowVersion",after.rowVersion());
        boolean detailedEvent = action.equals("edited") || action.equals("deleted");
        if (detailedEvent) {
            payload.put("contentId", items.findLocator(actor.companyId(), after.workItemId()).orElseThrow().contentId());
        }
        if (action.equals("edited")) {
            payload.put("previousStartedAt", before.startedAt());
            payload.put("previousStoppedAt", before.stoppedAt());
        }
        events.append(new EventDraft("workitem.time_tracking_"+action,detailedEvent ? 2 : 1,"TimeTrackingSession",after.id(),after.rowVersion(),actor.companyId(),EventActor.user(actor.userId()),json.valueToTree(payload)));
        audits.append(new SecurityAuditDraft(actor.companyId(),"time-tracking:"+after.id()+":"+after.rowVersion(),
                "TIME_TRACKING_"+action.toUpperCase(Locale.ROOT),SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.user(actor.userId(),actor.platformRoles().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet())),
                "TIME_TRACKING_SESSION",after.id().toString(),reason,json.valueToTree(before),json.valueToTree(after),null,null,null,null,now));
    }

    private TimeTrackingSession view(Session s, CurrentActor actor, boolean writable, Instant now, boolean disclose) {
        return view(s,actor,writable,now,disclose,users.findByUserIds(actor.companyId(),Set.of(s.userId())),
                timers.sessionShares(actor.companyId(),List.of(s.id()),now),null,null);
    }
    private TimeTrackingSession view(Session s, CurrentActor actor, boolean writable, Instant now, boolean disclose, Map<UUID,MinimalUserSnapshot> names, Map<UUID,long[]> allocated,String itemNo,String itemTitle) {
        String displayName=Optional.ofNullable(names.get(s.userId())).map(MinimalUserSnapshot::displayName).orElse("历史成员");
        long[] share=allocated.getOrDefault(s.id(),new long[2]);
        return new TimeTrackingSession(s.id(),disclose ? s.projectId() : null,disclose ? s.workItemId() : null,
                s.userId(),displayName,s.startedAt(),s.stoppedAt(),s.source(),Math.max(0,Duration.between(s.startedAt(),s.stoppedAt()==null ? now : s.stoppedAt()).toMillis()),
                s.rowVersion(),StrongEtag.format(s.rowVersion()),writable && s.userId().equals(actor.userId()) && s.stoppedAt()!=null && s.deletedAt()==null,s.deletedAt()!=null,s.changeReason(),share[0],(int)share[1],itemNo,itemTitle);
    }
    private WorkItemModels.WorkItemLocator locator(CurrentActor actor, UUID id) {
        if(id==null) throw validation("workItemId","REQUIRED","请选择工作项");
        var l=items.findLocator(actor.companyId(),id).orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        visible(actor,l.projectId()); return l;
    }
    private ProjectAccessSnapshot visible(CurrentActor actor, UUID projectId) {
        return access.findVisible(actor,projectId).orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }
    private static boolean canWrite(ProjectAccessSnapshot p) {
        return p.lifecycle()!=ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED && p.actorAccess()!=ProjectAccessSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY;
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private static void requireVersion(long actual,long expected) { if(actual!=expected) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT); }
    private static ApplicationException invalid(String reason) { return ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,reason); }
    private static ApplicationException validation(String field,String code,String message) { return ApplicationException.validation(new FieldViolation(field,code,message)); }
}
