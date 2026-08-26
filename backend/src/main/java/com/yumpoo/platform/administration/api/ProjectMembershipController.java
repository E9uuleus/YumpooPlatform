package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.administration.application.*;
import com.yumpoo.platform.catalog.api.*;
import com.yumpoo.platform.foundation.api.http.*;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@ApiV1Controller
public final class ProjectMembershipController {
    private final CurrentActorProvider actors;
    private final ProjectMembershipQuery query;
    private final ProjectMembershipGovernanceService service;
    private final IfMatchParser ifMatch;
    private final IdempotencyKeyParser idempotencyKeys;
    private final IdempotencyRequestHasher hasher;
    private final ObjectMapper objectMapper;

    public ProjectMembershipController(CurrentActorProvider actors, ProjectMembershipQuery query,
            ProjectMembershipGovernanceService service, IfMatchParser ifMatch,
            IdempotencyKeyParser idempotencyKeys, IdempotencyRequestHasher hasher,
            ObjectMapper objectMapper) {
        this.actors=actors; this.query=query; this.service=service; this.ifMatch=ifMatch;
        this.idempotencyKeys=idempotencyKeys; this.hasher=hasher; this.objectMapper=objectMapper;
    }

    @GetMapping("/projects/{projectId}/members")
    ProjectMemberPage members(@PathVariable UUID projectId,
            @RequestParam(required=false, defaultValue="ALL") String status,
            @RequestParam(required=false) String q,
            @RequestParam(required=false) Integer page, @RequestParam(required=false) Integer size) {
        if (q != null && q.strip().length() > 200)
            throw ApplicationException.validation(new FieldViolation("q", "INVALID_LENGTH",
                    "成员搜索关键字最多 200 个字符"));
        CurrentActor actor=actors.requiredActive();
        return query.findMembers(actor, projectId, parseStatus(status), q, OffsetPageRequest.of(page,size));
    }

    @GetMapping("/projects/{projectId}/member-candidates")
    ProjectMemberCandidatePage candidates(@PathVariable UUID projectId,
            @RequestParam String name, @RequestParam(required=false) Integer page,
            @RequestParam(required=false) Integer size) {
        if (name == null || name.isBlank() || name.strip().length()>200)
            throw ApplicationException.validation(new FieldViolation("name","INVALID_LENGTH",
                    "候选人名称必须为 1 到 200 个字符"));
        return query.findCandidates(actors.requiredActive(), projectId, name.strip(),
                OffsetPageRequest.of(page,size));
    }

    @PostMapping("/projects/{projectId}/members")
    ResponseEntity<String> add(@PathVariable UUID projectId, @Valid @RequestBody ProjectMemberAddRequest body,
            @RequestHeader(name=IfMatchParser.HEADER_NAME,required=false) String ifMatchHeader,
            @RequestHeader(name=IdempotencyKeyParser.HEADER_NAME,required=false) String idempotencyHeader) {
        CurrentActor actor=actors.requiredActive();
        query.requireVisible(actor, projectId);
        Long expected=ifMatchHeader==null?null:ifMatch.parseForVisibleResource(true,ifMatchHeader);
        UUID key=idempotencyKeys.parseRequired(idempotencyHeader);
        Map<String,String> scope=new LinkedHashMap<>(); scope.put("projectId",projectId.toString());
        scope.put("ifMatch",expected==null?"absent":Long.toString(expected));
        ProjectMemberGovernanceCommand command=new ProjectMemberGovernanceCommand(actor,projectId,
                body.userId(),expected,body.reason(),key,
                hasher.hash("addProjectMember",scope,objectMapper.valueToTree(body)),null,null);
        try { return stored(service.add(command).result(), projectId, body.userId()); }
        catch(RuntimeException failure) {
            service.recordFailed(actor,"PROJECT_MEMBER_ADD_FAILED","PROJECT_MEMBERSHIP",
                    projectId+":"+body.userId(),body.reason(),key,failure,null,null);
            throw failure;
        }
    }

    @DeleteMapping("/projects/{projectId}/members/{userId}")
    ResponseEntity<String> remove(@PathVariable UUID projectId, @PathVariable UUID userId,
            @RequestBody(required=false) ProjectMemberRemoveRequest body,
            @RequestHeader(name=IfMatchParser.HEADER_NAME,required=false) String ifMatchHeader,
            @RequestHeader(name=IdempotencyKeyParser.HEADER_NAME,required=false) String idempotencyHeader) {
        CurrentActor actor=actors.requiredActive(); query.requireVisible(actor,projectId);
        long expected=ifMatch.parseForVisibleResource(true,ifMatchHeader);
        UUID key=idempotencyKeys.parseRequired(idempotencyHeader);
        ProjectMemberRemoveRequest resolved=body==null?new ProjectMemberRemoveRequest(null):body;
        ProjectMemberGovernanceCommand command=new ProjectMemberGovernanceCommand(actor,projectId,userId,
                expected,resolved.reason(),key,
                hasher.hash("removeProjectMember",Map.of("projectId",projectId.toString(),
                        "userId",userId.toString(),"ifMatch",Long.toString(expected)),
                        objectMapper.valueToTree(resolved)),null,null);
        try { return stored(service.remove(command).result(),projectId,userId); }
        catch(RuntimeException failure) {
            service.recordFailed(actor,"PROJECT_MEMBER_REMOVE_FAILED","PROJECT_MEMBERSHIP",
                    projectId+":"+userId,resolved.reason(),key,failure,null,null);
            throw failure;
        }
    }

    @PostMapping("/projects/{projectId}/owner-reassignments")
    ResponseEntity<String> reassign(@PathVariable UUID projectId,
            @Valid @RequestBody ProjectOwnerReassignmentRequest body,
            @RequestHeader(name=IfMatchParser.HEADER_NAME,required=false) String ifMatchHeader,
            @RequestHeader(name=IdempotencyKeyParser.HEADER_NAME,required=false) String idempotencyHeader) {
        CurrentActor actor=actors.requiredActive(); query.requireVisible(actor,projectId);
        long expected=ifMatch.parseForVisibleResource(true,ifMatchHeader);
        UUID key=idempotencyKeys.parseRequired(idempotencyHeader);
        ProjectOwnerReassignmentCommand command=new ProjectOwnerReassignmentCommand(actor,projectId,expected,
                body.newOwnerUserId(),body.reason(),key,
                hasher.hash("reassignProjectOwner",Map.of("projectId",projectId.toString(),
                        "ifMatch",Long.toString(expected)),objectMapper.valueToTree(body)),null,null);
        try { return stored(service.reassignOwner(command).result(),projectId,body.newOwnerUserId()); }
        catch(RuntimeException failure) {
            service.recordFailed(actor,"PROJECT_OWNER_REASSIGN_FAILED","PROJECT",projectId.toString(),
                    body.reason(),key,failure,null,null);
            throw failure;
        }
    }

    private static ResponseEntity<String> stored(StoredCommandResult stored, UUID projectId, UUID userId) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setETag(stored.etag()); headers.setCacheControl(CacheControl.noStore());
        if (stored.httpStatus()==201) headers.setLocation(URI.create("/api/v1/projects/"+projectId+"/members/"+userId));
        return new ResponseEntity<>(stored.responseJson(),headers,HttpStatus.valueOf(stored.httpStatus()));
    }

    private static ProjectMembershipStatus parseStatus(String value) {
        try { return ProjectMembershipStatus.valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) {
            throw ApplicationException.validation(new FieldViolation("status","INVALID_VALUE",
                    "成员状态必须为 ACTIVE、REMOVED 或 ALL"));
        }
    }
}
