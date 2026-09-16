package com.yumpoo.platform.reporting.api;

import com.yumpoo.platform.catalog.api.MemberProjectQuery;
import com.yumpoo.platform.foundation.api.http.*;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.reporting.application.DashboardModels.*;
import com.yumpoo.platform.reporting.application.DashboardService;
import com.yumpoo.platform.workitem.api.WorkItemStatisticsQuery;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;

@ApiV1Controller
public class DashboardController {
    private final DashboardService service;
    private final MemberProjectQuery projects;
    private final CurrentActorProvider actors;
    private final IfMatchParser matches;
    private final IdempotencyKeyParser keys;
    private final IdempotencyRequestHasher hasher;
    private final ObjectMapper json;
    public DashboardController(DashboardService service, MemberProjectQuery projects, CurrentActorProvider actors,
            IfMatchParser matches, IdempotencyKeyParser keys, IdempotencyRequestHasher hasher, ObjectMapper json) {
        this.service = service; this.projects = projects; this.actors = actors; this.matches = matches;
        this.keys = keys; this.hasher = hasher; this.json = json;
    }
    @GetMapping("/me/dashboards")
    ResponseEntity<ListResponse> list() { return response(service.list(actors.requiredActive())); }
    @GetMapping("/me/dashboard-projects")
    ResponseEntity<MemberProjectQuery.Page> projects(@RequestParam(defaultValue="") String query,
            @RequestParam(defaultValue="false") boolean includeArchived, @RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="50") int limit) {
        return response(projects.search(actors.requiredActive(), query, includeArchived, offset, limit));
    }
    @GetMapping("/me/dashboards/{id}")
    ResponseEntity<View> get(@PathVariable UUID id) {
        var result = service.get(actors.requiredActive(), id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.etag()).body(result);
    }
    @PostMapping("/me/dashboards")
    ResponseEntity<String> create(@RequestBody Write body,
            @RequestHeader(name=IdempotencyKeyParser.HEADER_NAME,required=false) String key) {
        return command("POST", null, body, null, key);
    }
    @PatchMapping("/me/dashboards/{id}")
    ResponseEntity<String> update(@PathVariable UUID id, @RequestBody Write body,
            @RequestHeader(name=IfMatchParser.HEADER_NAME,required=false) String match,
            @RequestHeader(name=IdempotencyKeyParser.HEADER_NAME,required=false) String key) {
        return command("PATCH", id, body, match, key);
    }
    @DeleteMapping("/me/dashboards/{id}")
    ResponseEntity<String> delete(@PathVariable UUID id,
            @RequestHeader(name=IfMatchParser.HEADER_NAME,required=false) String match,
            @RequestHeader(name=IdempotencyKeyParser.HEADER_NAME,required=false) String key) {
        return command("DELETE", id, null, match, key);
    }
    @PostMapping("/me/dashboards/{id}/query")
    ResponseEntity<QueryResponse> query(@PathVariable UUID id, @RequestBody Query body) {
        return response(service.query(actors.requiredActive(), id, body));
    }
    @PostMapping("/me/dashboards/{id}/items/query")
    ResponseEntity<WorkItemStatisticsQuery.Page> items(@PathVariable UUID id, @RequestBody ItemsQuery body) {
        return response(service.items(actors.requiredActive(), id, body));
    }
    private ResponseEntity<String> command(String method, UUID id, Write body, String match, String key) {
        var actor = actors.requiredActive();
        if (id != null) service.requireOwned(actor, id, method.equals("DELETE"));
        long expected = id == null ? 0 : matches.parseForVisibleResource(true, match);
        var result = service.command(actor, method, id, body, expected, keys.parseRequired(key),
                hasher.hash("dashboard:" + method, Map.of("id", id == null ? "" : id.toString(), "version", Long.toString(expected)), json.valueToTree(body))).result();
        var response = ResponseEntity.status(result.httpStatus()).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore());
        if (result.etag() != null) response.eTag(result.etag());
        return response.body(result.responseJson());
    }
    private static <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
