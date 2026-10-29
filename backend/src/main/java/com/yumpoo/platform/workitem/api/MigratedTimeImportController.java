package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.foundation.api.http.*;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.workitem.application.MigratedTimeImportService;
import com.yumpoo.platform.workitem.application.MigratedTimeImportService.Request;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;

@ApiV1Controller
public class MigratedTimeImportController {
    private final CurrentActorProvider actors;
    private final MigratedTimeImportService service;
    private final CompanyTimeZonePort zones;
    private final IdempotencyKeyParser keys;
    private final IdempotencyRequestHasher hasher;
    private final ObjectMapper json;
    public MigratedTimeImportController(CurrentActorProvider actors, MigratedTimeImportService service, CompanyTimeZonePort zones,
            IdempotencyKeyParser keys, IdempotencyRequestHasher hasher, ObjectMapper json) {
        this.actors = actors; this.service = service; this.zones = zones; this.keys = keys; this.hasher = hasher; this.json = json;
    }

    @PostMapping("/company/migrated-time-imports")
    ResponseEntity<String> importTime(@RequestBody Request body,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String key) {
        var result = service.execute(actors.requiredActive(), zones.current(), body, keys.parseRequired(key),
                hasher.hash("migratedTime:import", Map.of(), json.valueToTree(body)));
        return ResponseEntity.status(result.httpStatus()).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(result.responseJson());
    }
}
