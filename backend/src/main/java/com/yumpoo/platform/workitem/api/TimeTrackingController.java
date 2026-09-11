package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.foundation.api.http.*;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.workitem.application.TimeTrackingService;
import com.yumpoo.platform.workitem.application.TimeTrackingService.Input;
import com.yumpoo.platform.workitem.application.TimeTrackingModels.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@ApiV1Controller
public class TimeTrackingController {
    private final CurrentActorProvider actors;
    private final TimeTrackingService service;
    private final IfMatchParser matches;
    private final IdempotencyKeyParser keys;
    private final IdempotencyRequestHasher hasher;
    private final ObjectMapper json;
    public TimeTrackingController(CurrentActorProvider actors,TimeTrackingService service,IfMatchParser matches,
            IdempotencyKeyParser keys,IdempotencyRequestHasher hasher,ObjectMapper json) {
        this.actors=actors;this.service=service;this.matches=matches;this.keys=keys;this.hasher=hasher;this.json=json;
    }
    @GetMapping("/me/time-tracker")
    ResponseEntity<CurrentTimeTracker> current() {
        var state=service.current(actors.requiredActive());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(state.etag()).body(state);
    }
    @GetMapping("/me/time-tracker/candidates")
    ResponseEntity<TimerCandidatePage> candidates(@RequestParam(required=false) String q,
            @RequestParam(defaultValue="PERSONAL") String scope, @RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="25") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.candidates(actors.requiredActive(), q, scope, offset, limit));
    }
    @GetMapping("/projects/{projectId}/time-tracking-summaries")
    ResponseEntity<SummaryPage> summaries(@PathVariable UUID projectId,@RequestParam List<UUID> workItemId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.summaries(actors.requiredActive(),projectId,workItemId));
    }
    @GetMapping("/work-items/{workItemId}/time-sessions")
    ResponseEntity<SessionPage> history(@PathVariable UUID workItemId,@RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(actors.requiredActive(),workItemId,cursor));
    }
    @PostMapping("/me/time-tracker/start")
    ResponseEntity<String> start(@RequestBody Input body,
            @RequestHeader(name=IfMatchParser.HEADER_NAME,required=false) String match,
            @RequestHeader(name=IdempotencyKeyParser.HEADER_NAME,required=false) String key) {
        return command("start",body,match,key);
    }
    @PostMapping("/me/time-tracker/switch")
    ResponseEntity<String> switchTimer(@RequestBody Input body,
            @RequestHeader(name=IfMatchParser.HEADER_NAME,required=false) String match,
            @RequestHeader(name=IdempotencyKeyParser.HEADER_NAME,required=false) String key) {
        return command("switch",body,match,key);
    }
    @PostMapping("/me/time-tracker/stop")
    ResponseEntity<String> stop(@RequestBody Input body,
            @RequestHeader(name=IfMatchParser.HEADER_NAME,required=false) String match,
            @RequestHeader(name=IdempotencyKeyParser.HEADER_NAME,required=false) String key) {
        return command("stop",body,match,key);
    }
    @PostMapping("/work-items/{workItemId}/time-sessions")
    ResponseEntity<String> create(@PathVariable UUID workItemId,@RequestBody Input body,
            @RequestHeader(name=IdempotencyKeyParser.HEADER_NAME,required=false) String key) {
        return command("create",new Input(workItemId,null,body.startedAt(),body.stoppedAt(),body.reason()),null,key);
    }
    @PatchMapping("/work-items/{workItemId}/time-sessions/{sessionId}")
    ResponseEntity<String> edit(@PathVariable UUID workItemId,@PathVariable UUID sessionId,@RequestBody Input body,
            @RequestHeader(name=IfMatchParser.HEADER_NAME,required=false) String match,
            @RequestHeader(name=IdempotencyKeyParser.HEADER_NAME,required=false) String key) {
        return command("edit",new Input(workItemId,sessionId,body.startedAt(),body.stoppedAt(),body.reason()),match,key);
    }
    @DeleteMapping("/work-items/{workItemId}/time-sessions/{sessionId}")
    ResponseEntity<String> delete(@PathVariable UUID workItemId,@PathVariable UUID sessionId,@RequestBody Input body,
            @RequestHeader(name=IfMatchParser.HEADER_NAME,required=false) String match,
            @RequestHeader(name=IdempotencyKeyParser.HEADER_NAME,required=false) String key) {
        return command("delete",new Input(workItemId,sessionId,null,null,body.reason()),match,key);
    }
    private ResponseEntity<String> command(String action,Input input,String match,String key) {
        var actor=actors.requiredActive();
        if(!action.equals("stop") && input.workItemId()!=null) service.history(actor,input.workItemId(),null);
        long expected=action.equals("create") ? 0 : matches.parseForVisibleResource(true,match);
        var result=service.command(actor,action,input,expected,keys.parseRequired(key),
                hasher.hash("timeTracking:"+action,Map.of("ifMatch",match==null ? "" : match),json.valueToTree(input)));
        return ResponseEntity.status(result.httpStatus()).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).eTag(result.etag()).body(result.responseJson());
    }
}
