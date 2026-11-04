package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.*;
import com.yumpoo.platform.identityaccess.api.*;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.*;
import com.yumpoo.platform.foundation.application.event.*;
import com.yumpoo.platform.foundation.application.idempotency.*;
import com.yumpoo.platform.audit.api.*;
import com.fasterxml.jackson.annotation.JsonInclude;
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
    /** Category fields are left out of JSON when absent, so request hashes of work-item-only bodies stay as they were. */
    public record Input(UUID workItemId, UUID sessionId, Instant startedAt, Instant stoppedAt, String reason, List<UUID> workItemIds,
            @JsonInclude(JsonInclude.Include.NON_NULL) TimeCategory timeCategory,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<TimeCategory> timeCategories,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID categoryProjectId,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<TimeCategorySubject> timeCategorySubjects) {}
    private final TimeTrackingRepository timers;
    private final TimeShareMaintainer shares;
    private final WorkItemRepository items;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectConnectionTargetQuery projectTargets;
    private final ProjectFactWriteGuard guard;
    private final ProjectDeletionQuery deletion;
    private final MinimalUserSnapshotQuery users;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final SecurityAuditAppendPort audits;
    private final ObjectMapper json;
    private final Clock clock;

    public TimeTrackingService(TimeTrackingRepository timers, TimeShareMaintainer shares, WorkItemRepository items,
            ProjectAccessSnapshotQuery access, ProjectConnectionTargetQuery projectTargets, ProjectFactWriteGuard guard, ProjectDeletionQuery deletion, MinimalUserSnapshotQuery users,
            IdempotentCommandExecutor idempotency, TransactionalEventPort events, SecurityAuditAppendPort audits,
            ObjectMapper json, Clock clock) {
        this.timers=timers; this.shares=shares; this.items=items; this.access=access; this.projectTargets=projectTargets; this.guard=guard; this.deletion=deletion; this.users=users;
        this.idempotency=idempotency; this.events=events; this.audits=audits; this.json=json; this.clock=clock;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public CurrentTimeTracker current(CurrentActor actor) {
        return currentView(actor);
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public TimerCandidatePage candidates(CurrentActor actor, String query, String scope, int offset, int limit,
            List<UUID> projectIds, boolean assignedToMe, boolean excludeCompleted) {
        String normalized=query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if(normalized.length()>200) throw validation("q", "INVALID_LENGTH", "搜索词最多 200 字");
        if(!Set.of("PERSONAL", "ALL").contains(scope)) throw validation("scope", "INVALID_VALUE", "无效的工作项范围");
        if(offset<0 || offset>100000 || limit<1 || limit>50) throw validation("offset", "INVALID_PAGE", "无效的分页参数");
        if(projectIds!=null && projectIds.size()>20) throw validation("projectId", "INVALID_SIZE", "最多选择 20 个项目");
        var projects=access.findWritableProjects(actor);
        if(projects.isEmpty()) return new TimerCandidatePage(List.of(), null, offset==0 ? List.of() : null, offset==0 ? 0 : null);
        var names=projects.stream().collect(java.util.stream.Collectors.toMap(
                ProjectAccessSnapshotQuery.WritableProject::projectId, ProjectAccessSnapshotQuery.WritableProject::name));
        var matching=projects.stream().filter(p -> !normalized.isEmpty()
                && (p.name().toLowerCase(Locale.ROOT).contains(normalized) || p.code().toLowerCase(Locale.ROOT).contains(normalized)))
                .map(ProjectAccessSnapshotQuery.WritableProject::projectId).toList();
        var filtered=new HashSet<>(names.keySet());
        if(projectIds!=null && !projectIds.isEmpty()) filtered.retainAll(projectIds);
        Instant now=now();
        var rows=timers.candidates(actor.companyId(), actor.userId(), names.keySet(), filtered, matching, normalized,
                scope.equals("PERSONAL"), assignedToMe, excludeCompleted, offset, limit+1, now);
        List<TimerCandidateProjectCount> counts=null;
        Integer hidden=null;
        if(offset==0) {
            var totals=timers.candidateProjectCounts(actor.companyId(), actor.userId(), names.keySet(), matching, normalized,
                    scope.equals("PERSONAL"), assignedToMe, excludeCompleted, now);
            counts=totals.stream().filter(row -> row.visible()>0)
                    .map(row -> new TimerCandidateProjectCount(row.projectId(), names.get(row.projectId()), row.visible()))
                    .sorted(Comparator.comparingLong(TimerCandidateProjectCount::count).reversed()
                            .thenComparing(TimerCandidateProjectCount::projectName).thenComparing(TimerCandidateProjectCount::projectId)).toList();
            hidden=Math.toIntExact(totals.stream().filter(row -> filtered.contains(row.projectId())).mapToLong(TimeTrackingRepository.CandidateProjectCount::hidden).sum());
        }
        return new TimerCandidatePage(rows.stream().limit(limit).map(row -> row.withProjectName(names.get(row.projectId()))).toList(),
                rows.size()>limit ? offset+limit : null, counts, hidden);
    }

    private CurrentTimeTracker currentView(CurrentActor actor) {
        long version=timers.stateVersion(actor.companyId(),actor.userId(),false);
        Instant now=now();
        var running=timers.running(actor.companyId(),actor.userId());
        var recent=timers.recentItems(actor.companyId(),actor.userId());
        var resume=timers.resumeItems(actor.companyId(),actor.userId());
        Set<UUID> projectIds=new HashSet<>();
        running.stream().map(Session::projectId).filter(Objects::nonNull).forEach(projectIds::add);
        recent.forEach(item -> projectIds.add(item.projectId()));
        resume.forEach(item -> projectIds.add(item.projectId()));
        var visibleProjects=access.findVisible(actor,projectIds);
        var recentItems=recent.stream().filter(item -> visibleProjects.containsKey(item.projectId())
                && visibleProjects.get(item.projectId()).contentWritable()).limit(5).toList();
        var resumeItems=resume.stream().filter(item -> visibleProjects.containsKey(item.projectId())
                && visibleProjects.get(item.projectId()).contentWritable()).toList();
        var titles=new HashMap<UUID,String>();
        running.stream().filter(s -> s.projectId()!=null && visibleProjects.containsKey(s.projectId())).collect(java.util.stream.Collectors.groupingBy(Session::projectId))
                .forEach((projectId,rows) -> items.findByIds(actor.companyId(),projectId,rows.stream().map(Session::workItemId).toList())
                        .forEach(item -> titles.put(item.id(),item.title())));
        var names=users.findByUserIds(actor.companyId(),Set.of(actor.userId()));
        var allocated=timers.sessionShares(actor.companyId(),running.stream().map(Session::id).toList(),now);
        // A category timer has no title to look up; clients label it from session.timeCategory.
        var views=running.stream().map(s -> new CurrentRunningTimer(view(s,actor,false,now,
                s.timeCategory()!=null || titles.containsKey(s.workItemId()),names,allocated,null,null),
                s.timeCategory()!=null ? null : titles.get(s.workItemId()))).toList();
        var subjects=timers.resumeCategories(actor.companyId(),actor.userId());
        return CurrentTimeTracker.of(views,version,now,recentItems,resumeItems,
                subjects.stream().map(TimeCategorySubject::timeCategory).distinct().sorted().toList(),subjects,preferences(actor));
    }

    private TimeTrackingPreferences preferences(CurrentActor actor) {
        return timers.preferences(actor.companyId(),actor.userId()).orElse(TimeTrackingPreferences.DEFAULT);
    }

    public StoredCommandResult updatePreferences(CurrentActor actor,TimeTrackingPreferencesCommand command,long expectedVersion,UUID key,RequestHash hash) {
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(actor.userId(),
                "PUT","timeTracking:preferences",key),hash),() -> updatePreferences(actor,command,expectedVersion)).result();
    }

    private StoredCommandResult updatePreferences(CurrentActor actor,TimeTrackingPreferencesCommand command,long expectedVersion) {
        if(command==null || command.timerEnabled()==null) throw validation("timerEnabled","REQUIRED","请选择是否使用计时器");
        if(command.workdayStartMinute()==null) throw validation("workdayStartMinute","REQUIRED","请设置上班时间");
        if(command.workdayEndMinute()==null) throw validation("workdayEndMinute","REQUIRED","请设置下班时间");
        int start=command.workdayStartMinute(), end=command.workdayEndMinute();
        if(start<0 || start>1439 || end<1 || end>1440 || end<=start)
            throw validation("workdayEndMinute","INVALID_RANGE","下班时间须晚于上班时间，且都在当天之内");
        var currentPreferences=preferences(actor);
        var next=new TimeTrackingPreferences(command.timerEnabled(),start,end,
                command.hideCompleted()==null ? currentPreferences.hideCompleted() : command.hideCompleted(),
                command.hideCompletedEffect()==null ? currentPreferences.hideCompletedEffect() : command.hideCompletedEffect());
        Set<UUID> projects=new TreeSet<>();
        if(!next.timerEnabled()) {
            timers.running(actor.companyId(),actor.userId()).stream().map(Session::projectId).filter(Objects::nonNull).forEach(projects::add);
            lockForProjection(actor,projects);
        }
        long version=timers.stateVersion(actor.companyId(),actor.userId(),true);
        requireVersion(version,expectedVersion);
        var stopping=next.timerEnabled() ? List.<Session>of() : timers.running(actor.companyId(),actor.userId());
        if(stopping.stream().anyMatch(s -> s.projectId()!=null && !projects.contains(s.projectId()))) throw invalid("TIMER_CHANGED");
        if(!stopping.isEmpty() || !next.equals(preferences(actor))) {
            Instant now=now();
            var stopped=stopSessions(actor,stopping,now);
            timers.savePreferences(actor.companyId(),actor.userId(),next);
            if(!stopped.isEmpty()) rebuild(actor.companyId(),actor.userId(),now,null,stopped);
            timers.advanceState(actor.companyId(),actor.userId());
        }
        CurrentTimeTracker current=currentView(actor);
        return new StoredCommandResult(200,json.writeValueAsString(current),actor.userId(),current.etag());
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

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public MyTimeSessionPage mySessions(CurrentActor actor,Instant from,Instant to) {
        if(from==null || to==null || !to.isAfter(from) || Duration.between(from,to).compareTo(Duration.ofDays(7))>0)
            throw validation("to","INVALID_RANGE","结束时间须晚于开始时间，且查询范围不能超过 7 天");
        var rows=timers.mySessions(actor.companyId(),actor.userId(),from,to);
        var projects=access.findVisible(actor,rows.stream().flatMap(row -> java.util.stream.Stream.of(row.session().projectId(),row.session().categoryProjectId())).filter(Objects::nonNull).distinct().toList());
        var projectSnapshots=projectTargets.findByIds(actor.companyId(),projects.keySet());
        Instant now=now();
        var names=users.findByUserIds(actor.companyId(),Set.of(actor.userId()));
        var allocated=timers.sessionShares(actor.companyId(),rows.stream().map(row -> row.session().id()).toList(),now);
        return new MyTimeSessionPage(rows.stream().map(row -> {
            // Category records remain personal; a linked project name is disclosed only while visible.
            if(row.session().timeCategory()!=null) {
                UUID tagged=row.session().categoryProjectId();
                return new MyTimeSession(view(row.session(),actor,row.session().timeCategory().userSelectable(),now,true,names,allocated,null,null),
                        tagged!=null && projectSnapshots.containsKey(tagged) ? projectSnapshots.get(tagged).name() : null,null,null,null);
            }
            var project=projects.get(row.session().projectId());
            boolean disclose=project!=null;
            boolean writable=disclose && canWrite(project) && !row.archived() && !row.itemDeleted();
            return new MyTimeSession(view(row.session(),actor,writable,now,disclose,names,allocated,null,null),
                    disclose ? projectSnapshots.get(row.session().projectId()).name() : null,disclose ? row.itemNo() : null,
                    disclose ? row.title() : null,disclose ? row.contentColorToken() : null);
        }).toList(),now);
    }

    public StoredCommandResult fill(CurrentActor actor,TimeSessionFillCommand command,UUID key,RequestHash hash) {
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(actor.userId(),
                "POST","timeTracking:fill",key),hash),() -> fill(actor,command)).result();
    }

    private StoredCommandResult fill(CurrentActor actor,TimeSessionFillCommand command) {
        if(command.mode()==null) throw validation("mode","REQUIRED","请选择补录方式");
        if(command.entries()==null || command.entries().isEmpty() || command.entries().size()>20)
            throw validation("entries","INVALID_SIZE","一次须提交 1 到 20 条记录");
        Instant now=now();
        var entries=new ArrayList<TimeSessionFillEntry>();
        for(var entry:command.entries()) {
            Instant start=entry.startedAt()==null ? null : entry.startedAt().truncatedTo(ChronoUnit.MILLIS);
            Instant stop=entry.stoppedAt()==null ? null : entry.stoppedAt().truncatedTo(ChronoUnit.MILLIS);
            if(start==null || stop==null || !stop.isAfter(start) || stop.isAfter(now))
                throw validation("stoppedAt","INVALID_RANGE","结束时间须晚于开始时间且不能在未来");
            entries.add(TimeSessionFillEntry.of(entry.subject(),start,stop));
        }
        var result=applyFill(actor,command.mode(),entries,null,0);
        return new StoredCommandResult(200,json.writeValueAsString(result),actor.userId(),null);
    }

    public StoredCommandResult reassign(CurrentActor actor,UUID sessionId,TimeSessionReassignCommand body,long expectedVersion,UUID key,RequestHash hash) {
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(actor.userId(),
                "POST","timeTracking:reassign",key),hash),() -> reassign(actor,sessionId,body,expectedVersion)).result();
    }

    private StoredCommandResult reassign(CurrentActor actor,UUID sessionId,TimeSessionReassignCommand body,long expectedVersion) {
        var target=TimeSubject.of(body.workItemId(),body.timeCategory(),body.categoryProjectId());
        Session session=timers.find(actor.companyId(),sessionId).filter(s -> s.userId().equals(actor.userId()))
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireNotMigrated(session);
        requireVersion(session.rowVersion(),expectedVersion);
        if(session.stoppedAt()==null || session.deletedAt()!=null) throw invalid("SESSION_NOT_EDITABLE");
        if(session.subject().equals(target))
            throw validation("workItemId","UNCHANGED",target.timeCategory()!=null ? "记录已属于该分类" : "记录已属于该工作项");
        var result=applyFill(actor,TimeSessionFillMode.PARALLEL,
                List.of(TimeSessionFillEntry.of(target,session.startedAt(),session.stoppedAt())),session,expectedVersion);
        return new StoredCommandResult(200,json.writeValueAsString(result),actor.userId(),null);
    }

    /** Fills the entries and, when {@code removing} is given, soft-deletes that session under the same locks first. */
    private TimeSessionFillResult applyFill(CurrentActor actor,TimeSessionFillMode mode,List<TimeSessionFillEntry> entries,
            Session removing,long expectedVersion) {
        Instant now=now();
        requireSelectable(entries.stream().map(TimeSessionFillEntry::subject).toList());
        var merged=TimeSessionFillPlanner.mergeEntries(entries);
        if(TimeSessionFillPlanner.slotsOverlap(merged))
            throw validation("entries","CATEGORY_OVERLAP","同一分类在同一时段只能关联一个项目");
        requireCategoryProjects(actor,merged.stream().map(TimeSessionFillEntry::subject).toList());
        Instant from=merged.stream().map(TimeSessionFillEntry::startedAt).min(Instant::compareTo).orElseThrow();
        Instant to=merged.stream().map(TimeSessionFillEntry::stoppedAt).max(Instant::compareTo).orElseThrow();
        // Only work items carry a project to guard; categories are the owner's own and take no project lock.
        var targets=merged.stream().map(TimeSessionFillEntry::workItemId).filter(Objects::nonNull).distinct().map(id -> locator(actor,id)).toList();
        var targetProjects=targets.stream().map(WorkItemModels.WorkItemLocator::projectId).collect(java.util.stream.Collectors.toSet());
        var observed=timers.mySessions(actor.companyId(),actor.userId(),from,to);
        var editable=mode==TimeSessionFillMode.REPLACE ? editableSessionIds(actor,observed) : Set.<UUID>of();
        Set<UUID> lockedProjects=new TreeSet<>(targetProjects);
        observed.stream().filter(row -> editable.contains(row.session().id()) && row.session().projectId()!=null)
                .forEach(row -> lockedProjects.add(row.session().projectId()));
        if(removing!=null && removing.projectId()!=null) lockedProjects.add(removing.projectId());
        for(UUID projectId:lockedProjects) {
            var project=guard.lockForFactWrite(actor,projectId);
            if(removing!=null && projectId.equals(removing.projectId()) && !targetProjects.contains(projectId)
                    && (project.actorAccess()==ProjectFactWriteSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY
                    || project.lifecycle()==ProjectFactWriteSnapshot.ProjectLifecycle.ARCHIVED)) throw invalid("SESSION_NOT_EDITABLE");
            if(targetProjects.contains(projectId)) {
                if(project.actorAccess()==ProjectFactWriteSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY)
                    throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
                if(project.lifecycle()==ProjectFactWriteSnapshot.ProjectLifecycle.ARCHIVED) throw invalid("PROJECT_ARCHIVED");
            }
        }
        timers.stateVersion(actor.companyId(),actor.userId(),true);
        Session removed=null;
        if(removing!=null) {
            removed=timers.find(actor.companyId(),removing.id()).filter(s -> s.userId().equals(actor.userId()))
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            requireVersion(removed.rowVersion(),expectedVersion);
            if(removed.stoppedAt()==null || removed.deletedAt()!=null) throw invalid("SESSION_NOT_EDITABLE");
        }
        var rows=timers.mySessions(actor.companyId(),actor.userId(),from,to);
        var editableIds=mode==TimeSessionFillMode.REPLACE ? editableSessionIds(actor,rows) : new HashSet<UUID>();
        if(rows.stream().anyMatch(row -> editableIds.contains(row.session().id()) && row.session().projectId()!=null
                && !lockedProjects.contains(row.session().projectId())))
            throw invalid("TIMER_CHANGED");
        Map<UUID,UUID> itemProjects=new TreeMap<>();
        targets.forEach(target -> itemProjects.put(target.workItemId(),target.projectId()));
        rows.stream().filter(row -> editableIds.contains(row.session().id()) && row.session().workItemId()!=null)
                .forEach(row -> itemProjects.put(row.session().workItemId(),row.session().projectId()));
        if(removed!=null && removed.workItemId()!=null) itemProjects.put(removed.workItemId(),removed.projectId());
        var targetIds=targets.stream().map(WorkItemModels.WorkItemLocator::workItemId).collect(java.util.stream.Collectors.toSet());
        UUID removedItem=removed==null ? null : removed.workItemId();
        for(var entry:itemProjects.entrySet()) {
            var locked=items.lockProjectItem(actor.companyId(),entry.getValue(),entry.getKey());
            if(locked.isEmpty()) {
                if(targetIds.contains(entry.getKey())) throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
                if(entry.getKey().equals(removedItem)) throw invalid("SESSION_NOT_EDITABLE");
                rows.stream().filter(row -> entry.getKey().equals(row.session().workItemId())).forEach(row -> editableIds.remove(row.session().id()));
            }
        }
        UUID removedId=removed==null ? null : removed.id();
        var plan=TimeSessionFillPlanner.plan(mode,merged,rows.stream().map(MySessionRow::session)
                .filter(session -> !session.id().equals(removedId)).toList(),editableIds);
        Set<UUID> written=new HashSet<>();
        if(removed!=null) {
            Session after=removed.next(removed.startedAt(),removed.stoppedAt(),now,null);
            timers.save(after); publish(actor,"deleted",removed,after,null,now); written.add(after.projectId());
        }
        for(var change:plan.changed()) {
            Session before=change.before();
            Session after=before.next(change.startedAt(),change.stoppedAt(),null,null);
            timers.save(after); publish(actor,"edited",before,after,null,now); written.add(after.projectId());
            if(before.startedAt().isBefore(from)) from=before.startedAt();
            if(before.stoppedAt().isAfter(to)) to=before.stoppedAt();
        }
        for(Session before:plan.deleted()) {
            Session after=before.next(before.startedAt(),before.stoppedAt(),now,null);
            timers.save(after); publish(actor,"deleted",before,after,null,now); written.add(after.projectId());
            if(before.startedAt().isBefore(from)) from=before.startedAt();
            if(before.stoppedAt().isAfter(to)) to=before.stoppedAt();
        }
        for(var entry:plan.created()) {
            Session created=Session.create(actor.companyId(),entry.workItemId()==null ? null : itemProjects.get(entry.workItemId()),
                    entry.subject(),actor.userId(),entry.startedAt(),entry.stoppedAt(),"MANUAL");
            timers.save(created); publish(actor,"added",null,created,null,now); written.add(created.projectId());
            if(created.startedAt().isBefore(from)) from=created.startedAt();
            if(created.stoppedAt().isAfter(to)) to=created.stoppedAt();
        }
        if(!written.isEmpty()) rebuild(actor.companyId(),actor.userId(),from,to,written);
        return new TimeSessionFillResult(plan.created().size(),plan.changed().size(),
                plan.deleted().size()+(removed==null ? 0 : 1),plan.skippedDurationMs());
    }

    private Set<UUID> editableSessionIds(CurrentActor actor,List<MySessionRow> rows) {
        var projects=access.findVisible(actor,rows.stream().map(row -> row.session().projectId()).filter(Objects::nonNull).distinct().toList());
        return rows.stream().filter(row -> row.session().stoppedAt()!=null && row.session().deletedAt()==null
                && !row.session().migratedImport()
                && (row.session().timeCategory()!=null || !row.archived() && !row.itemDeleted() && projects.containsKey(row.session().projectId())
                && canWrite(projects.get(row.session().projectId()))))
                .map(row -> row.session().id()).collect(java.util.stream.Collectors.toSet());
    }

    public StoredCommandResult command(CurrentActor actor, String action, Input input, long expectedVersion,
            UUID key, RequestHash hash) {
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(actor.userId(),
                "POST","timeTracking:"+action,key),hash), () -> mutate(actor,action,input,expectedVersion)).result();
    }

    private StoredCommandResult mutate(CurrentActor actor, String action, Input input, long expectedVersion) {
        boolean timerCommand=Set.of("start","switch","stop","stopAll").contains(action);
        List<TimeSubject> requestedSubjects;
        if(Set.of("stop","stopAll").contains(action)) requestedSubjects=List.of();
        else if(action.equals("start") && (input.workItemIds()!=null || input.timeCategories()!=null || input.timeCategorySubjects()!=null)) {
            var batch=new ArrayList<TimeSubject>();
            if(input.workItemIds()!=null) input.workItemIds().forEach(id -> batch.add(TimeSubject.of(id,null)));
            if(input.timeCategories()!=null) input.timeCategories().forEach(category -> batch.add(TimeSubject.of(null,category)));
            if(input.timeCategorySubjects()!=null) input.timeCategorySubjects().forEach(s -> batch.add(TimeSubject.of(null,s.timeCategory(),s.projectId())));
            if(batch.isEmpty() || batch.size()>10)
                throw validation("workItemIds","INVALID_SIZE","一次最多选择 10 项");
            requestedSubjects=batch;
        } else requestedSubjects=List.of(TimeSubject.of(input.workItemId(),input.timeCategory(),input.categoryProjectId()));
        var subjects=requestedSubjects.stream().distinct().toList();
        requireSelectable(subjects);
        var categories=subjects.stream().map(TimeSubject::timeCategory).filter(Objects::nonNull).toList();
        if(categories.size()!=categories.stream().distinct().count())
            throw validation("timeCategorySubjects","DUPLICATE_CATEGORY","同一分类一次只能关联一个项目");
        requireCategoryProjects(actor,subjects);
        var targets=subjects.stream().map(TimeSubject::workItemId).filter(Objects::nonNull).map(id -> locator(actor,id)).toList();
        var itemProjects=new HashMap<UUID,UUID>();
        targets.forEach(target -> itemProjects.put(target.workItemId(),target.projectId()));
        var observedRunning=timerCommand ? timers.running(actor.companyId(),actor.userId()) : List.<Session>of();
        Set<UUID> projects=new TreeSet<>();
        targets.forEach(target -> projects.add(target.projectId()));
        if(timerCommand) {
            if(action.equals("stop")) timers.find(actor.companyId(),input.sessionId()).map(Session::projectId).ifPresent(projects::add);
            if(action.equals("switch") || action.equals("stopAll"))
                observedRunning.stream().map(Session::projectId).filter(Objects::nonNull).forEach(projects::add);
            lockForProjection(actor,projects);
        }
        for(UUID projectId:targets.stream().map(WorkItemModels.WorkItemLocator::projectId).distinct().sorted().toList()) {
            var project=guard.lockForFactWrite(actor,projectId);
            if(project.actorAccess()==ProjectFactWriteSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY)
                throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
            if(project.lifecycle()==ProjectFactWriteSnapshot.ProjectLifecycle.ARCHIVED) throw invalid("PROJECT_ARCHIVED");
        }
        long stateVersion=timers.stateVersion(actor.companyId(),actor.userId(),true);
        Instant now; Set<UUID> changedProjects=new HashSet<>();
        Object result; String etag; UUID resourceId;
        if(timerCommand) {
            requireVersion(stateVersion,expectedVersion);
            if(Set.of("start","switch").contains(action) && !preferences(actor).timerEnabled()) throw invalid("TIMER_DISABLED");
            var running=timers.running(actor.companyId(),actor.userId());
            var target=subjects.isEmpty() ? null : subjects.getFirst();
            var stopping=switch(action) {
                case "switch" -> running.stream().filter(s -> !s.subject().equals(target)).toList();
                case "stop" -> running.stream().filter(s -> s.id().equals(input.sessionId())).toList();
                case "stopAll" -> running;
                default -> running.stream().filter(s -> s.timeCategory()!=null && subjects.stream()
                        .anyMatch(t -> t.timeCategory()==s.timeCategory() && !t.equals(s.subject()))).toList();
            };
            if(action.equals("stop") && stopping.isEmpty()) throw invalid("TIMER_CHANGED");
            if(stopping.stream().anyMatch(s -> s.projectId()!=null && !projects.contains(s.projectId()))) throw invalid("TIMER_CHANGED");
            for(var item:targets.stream().sorted(Comparator.comparing(WorkItemModels.WorkItemLocator::workItemId)).toList())
                items.lockProjectItem(actor.companyId(),item.projectId(),item.workItemId())
                        .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            now=now();
            changedProjects.addAll(stopSessions(actor,stopping,now));
            for(var subject:subjects) if(running.stream().noneMatch(s -> s.subject().equals(subject))) {
                Session created=Session.create(actor.companyId(),subject.workItemId()==null ? null : itemProjects.get(subject.workItemId()),
                        subject,actor.userId(),now,null,"TIMER");
                timers.save(created); publish(actor,"started",null,created,null,now); changedProjects.add(created.projectId());
            }
            if(!changedProjects.isEmpty()) {
                rebuild(actor.companyId(),actor.userId(),now,null,changedProjects);
                timers.advanceState(actor.companyId(),actor.userId());
            }
            CurrentTimeTracker current=currentView(actor); result=current; etag=current.etag(); resourceId=actor.userId();
        } else {
            var target=targets.getFirst();
            items.lockProjectItem(actor.companyId(),target.projectId(),target.workItemId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            now=now();
            boolean create=action.equals("create"); boolean delete=action.equals("delete");
            Session before=create ? null : timers.find(actor.companyId(),input.sessionId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            if(before!=null) {
                if(!input.workItemId().equals(before.workItemId())) throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
                if(!before.userId().equals(actor.userId())) throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
                requireNotMigrated(before);
                requireVersion(before.rowVersion(),expectedVersion);
                if(before.stoppedAt()==null || before.deletedAt()!=null) throw invalid("SESSION_NOT_EDITABLE");
            }
            String reason=reason(input);
            Instant start=delete ? before.startedAt() : millis(input.startedAt());
            Instant stop=delete ? before.stoppedAt() : millis(input.stoppedAt());
            if(!delete) requireRange(start,stop,now);
            UUID id=create ? UUID.randomUUID() : before.id();
            if(!delete && timers.sameSubjectOverlaps(actor.companyId(),actor.userId(),new TimeSubject.Item(target.workItemId()),start,stop,id))
                throw validation("startedAt","TIME_OVERLAP","与该工作项的其他计时记录重叠");
            Session after=new Session(id,actor.companyId(),target.projectId(),target.workItemId(),actor.userId(),
                    start,stop,create ? "MANUAL" : before.source(),
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

    /** Edits or deletes one of the actor's own records, whatever it is recorded against; others' records are not found. */
    public StoredCommandResult ownSession(CurrentActor actor,String action,UUID sessionId,Input input,long expectedVersion,UUID key,RequestHash hash) {
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(actor.userId(),
                "POST","timeTracking:"+action+"Own",key),hash),() -> ownSession(actor,action,sessionId,input,expectedVersion)).result();
    }

    private StoredCommandResult ownSession(CurrentActor actor,String action,UUID sessionId,Input input,long expectedVersion) {
        Session session=timers.find(actor.companyId(),sessionId).filter(s -> s.userId().equals(actor.userId()))
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireNotMigrated(session);
        if(session.workItemId()!=null) return mutate(actor,action,new Input(session.workItemId(),sessionId,input.startedAt(),
                input.stoppedAt(),input.reason(),null,null,null,null,null),expectedVersion);
        timers.stateVersion(actor.companyId(),actor.userId(),true);
        Session before=timers.find(actor.companyId(),sessionId).orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(before.rowVersion(),expectedVersion);
        if(before.stoppedAt()==null || before.deletedAt()!=null) throw invalid("SESSION_NOT_EDITABLE");
        boolean delete=action.equals("delete");
        Instant now=now();
        String reason=reason(input);
        Instant start=delete ? before.startedAt() : millis(input.startedAt());
        Instant stop=delete ? before.stoppedAt() : millis(input.stoppedAt());
        if(!delete) requireRange(start,stop,now);
        if(!delete && timers.sameSubjectOverlaps(actor.companyId(),actor.userId(),before.subject(),start,stop,before.id()))
            throw validation("startedAt","TIME_OVERLAP","与该分类的其他计时记录重叠");
        Session after=before.next(start,stop,delete ? now : null,reason);
        timers.save(after);
        Instant from=after.startedAt().isBefore(before.startedAt()) ? after.startedAt() : before.startedAt();
        Instant to=after.stoppedAt().isAfter(before.stoppedAt()) ? after.stoppedAt() : before.stoppedAt();
        rebuild(actor.companyId(),actor.userId(),from,to,Set.of());
        publish(actor,delete ? "deleted" : "edited",before,after,reason,now);
        var view=view(after,actor,true,now,true);
        return new StoredCommandResult(200,json.writeValueAsString(view),after.id(),view.etag());
    }

    /** Runs inside the archive transaction after the project row lock, matching the project-then-timer_state order of timer commands. */
    @Transactional(propagation=Propagation.MANDATORY)
    public int stopRunningInArchivedProject(CurrentActor actor, UUID projectId) {
        return stopRunningInProject(actor, projectId, timers.runningUserIds(actor.companyId(), projectId));
    }

    /** Runs inside the leave transaction after the project row lock; stops only the leaving member's timers in that project. */
    @Transactional(propagation=Propagation.MANDATORY)
    public int stopRunningForLeavingMember(CurrentActor actor, UUID projectId) {
        return stopRunningInProject(actor, projectId, List.of(actor.userId()));
    }

    private int stopRunningInProject(CurrentActor actor, UUID projectId, List<UUID> userIds) {
        Instant now=now(); int stoppedCount=0; Set<UUID> affected=new TreeSet<>();
        for(UUID userId:userIds) {
            timers.stateVersion(actor.companyId(),userId,true);
            var running=timers.running(actor.companyId(),userId).stream().filter(s -> projectId.equals(s.projectId())).toList();
            if(running.isEmpty()) continue;
            for(Session before:running) {
                Session stopped=before.next(before.startedAt(),now.isBefore(before.startedAt()) ? before.startedAt() : now,null,null);
                timers.save(stopped); publish(actor,"stopped",before,stopped,null,now); stoppedCount++;
            }
            affected.addAll(shares.rebuild(actor.companyId(),userId,now,null));
            timers.advanceState(actor.companyId(),userId);
        }
        if(stoppedCount>0) {
            affected.add(projectId);
            for(UUID id:affected) {
                if(id.equals(projectId)) timers.advanceProjects(actor.companyId(),List.of(id));
                else timers.touchProjects(actor.companyId(),List.of(id));
            }
        }
        return stoppedCount;
    }

    private void lockForProjection(CurrentActor actor,Set<UUID> projectIds) {
        for(UUID projectId:new TreeSet<>(projectIds)) {
            deletion.lockForProjection(actor.companyId(),projectId).filter(project -> project.purgeStartedAt()==null)
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        }
    }

    private Set<UUID> stopSessions(CurrentActor actor,List<Session> sessions,Instant now) {
        Set<UUID> changed=new HashSet<>();
        for(Session before:sessions) {
            Session stopped=before.next(before.startedAt(),now.isBefore(before.startedAt()) ? before.startedAt() : now,null,null);
            timers.save(stopped); publish(actor,"stopped",before,stopped,null,now); changed.add(stopped.projectId());
        }
        return changed;
    }

    /** {@code projects} may hold null for category records, which belong to no project. */
    private void rebuild(UUID companyId,UUID userId,Instant from,Instant to,Set<UUID> projects) {
        var written=projects.stream().filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        var affected=new TreeSet<>(shares.rebuild(companyId,userId,from,to));
        affected.addAll(written);
        for(UUID id:affected) {
            if(written.contains(id)) timers.advanceProjects(companyId,List.of(id));
            else timers.touchProjects(companyId,List.of(id));
        }
    }

    /** Category records are no work item fact: they reach the security audit but publish no workitem domain event. */
    private void publish(CurrentActor actor, String action, Session before, Session after, String reason, Instant now) {
        if(after.workItemId()!=null) publishEvent(actor,action,before,after);
        audits.append(new SecurityAuditDraft(actor.companyId(),"time-tracking:"+after.id()+":"+after.rowVersion(),
                "TIME_TRACKING_"+action.toUpperCase(Locale.ROOT),SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.user(actor.userId(),actor.platformRoles().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet())),
                "TIME_TRACKING_SESSION",after.id().toString(),reason,json.valueToTree(before),json.valueToTree(after),null,null,null,null,now));
    }

    private void publishEvent(CurrentActor actor, String action, Session before, Session after) {
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
                s.rowVersion(),StrongEtag.format(s.rowVersion()),writable && !s.migratedImport() && s.userId().equals(actor.userId()) && s.stoppedAt()!=null && s.deletedAt()==null,s.deletedAt()!=null,s.changeReason(),share[0],(int)share[1],itemNo,itemTitle,s.timeCategory(),s.categoryProjectId(),s.migratedImport());
    }
    private static String reason(Input input) {
        String reason=input.reason()==null || input.reason().isBlank() ? null : input.reason().strip();
        if(reason!=null && reason.length()>500) throw validation("reason","INVALID_LENGTH","原因最多 500 字");
        return reason;
    }
    private static Instant millis(Instant value) { return value==null ? null : value.truncatedTo(ChronoUnit.MILLIS); }
    private static void requireRange(Instant start,Instant stop,Instant now) {
        if(start==null || stop==null || !stop.isAfter(start) || stop.isAfter(now))
            throw validation("stoppedAt","INVALID_RANGE","结束时间须晚于开始时间且不能在未来");
    }
    /** Reserved categories are written by the system only, never chosen by a user. */
    private static void requireSelectable(Collection<TimeSubject> subjects) {
        if(subjects.stream().map(TimeSubject::timeCategory).anyMatch(category -> category!=null && !category.userSelectable()))
            throw validation("timeCategory","TIME_CATEGORY_RESERVED","该工时分类由系统写入，不能选择");
    }
    private static void requireNotMigrated(Session session) {
        if(session.migratedImport()) throw invalid("MIGRATED_TIME_READ_ONLY");
    }
    /** Categories may name a project only among the actor's writable ones; categories take no project lock. */
    private void requireCategoryProjects(CurrentActor actor,Collection<TimeSubject> subjects) {
        var wanted=subjects.stream().map(TimeSubject::categoryProjectId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if(wanted.isEmpty()) return;
        var writable=access.findWritableProjects(actor).stream().map(ProjectAccessSnapshotQuery.WritableProject::projectId).collect(java.util.stream.Collectors.toSet());
        if(!writable.containsAll(wanted)) throw validation("categoryProjectId","PROJECT_UNAVAILABLE","无法关联该项目");
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
        return p.contentWritable() && p.actorAccess()!=ProjectAccessSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY;
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private static void requireVersion(long actual,long expected) { if(actual!=expected) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT); }
    private static ApplicationException invalid(String reason) { return ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,reason); }
    private static ApplicationException validation(String field,String code,String message) { return ApplicationException.validation(new FieldViolation(field,code,message)); }
}
