package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.api.web.HttpAccessLog;
import jakarta.servlet.http.HttpServletRequest;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.workitem.application.WorkItemImportModels.Command;
import com.yumpoo.platform.workitem.application.WorkItemImportModels.Field;
import com.yumpoo.platform.workitem.application.WorkItemImportModels.Row;
import com.yumpoo.platform.workitem.application.WorkItemImportService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.ObjectMapper;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

@ApiV1Controller
public final class WorkItemImportController {
    private final CurrentActorProvider actors;
    private final WorkItemImportService service;
    private final IdempotencyKeyParser keys;
    private final IdempotencyRequestHasher hasher;
    private final ObjectMapper objectMapper;

    public WorkItemImportController(CurrentActorProvider actors, WorkItemImportService service,
            IdempotencyKeyParser keys, IdempotencyRequestHasher hasher, ObjectMapper objectMapper) {
        this.actors = actors;
        this.service = service;
        this.keys = keys;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/projects/{projectId}/work-item-imports")
    ResponseEntity<String> importWorkItems(@PathVariable UUID projectId,
            @Valid @RequestBody WorkItemImportRequest body,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader, HttpServletRequest httpRequest) {
        HttpAccessLog.stage(httpRequest, body.dryRun() ? "PREVIEW" : "COMMIT");
        CurrentActor actor = actors.requiredActive();
        UUID key = keys.parseRequired(idempotencyHeader);
        Set<Field> fields = fields(body.fields());
        List<Row> rows = IntStream.range(0, body.rows().size()).mapToObj(index -> {
            WorkItemImportRequest.Row row = body.rows().get(index);
            if (new HashSet<>(row.assigneeUserIds()).size() != row.assigneeUserIds().size())
                throw validation("rows[" + index + "].assigneeUserIds", "DUPLICATE",
                        "处理人不可重复");
            return new Row(row.rowNumber(), row.title(), row.contentId(), row.statusCode(),
                    row.priority(), row.assigneeUserIds(), row.dueDate(), row.parentTitle(),
                    row.notes());
        }).toList();
        StoredCommandResult stored = service.importItems(new Command(actor, projectId,
                body.dryRun(), body.duplicateMode(), fields, rows, key,
                hasher.hash("importWorkItems", Map.of("projectId", projectId.toString()),
                        objectMapper.valueToTree(body)))).result();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        return new ResponseEntity<>(stored.responseJson(), headers,
                HttpStatus.valueOf(stored.httpStatus()));
    }

    private static Set<Field> fields(List<Field> requested) {
        Set<Field> fields = EnumSet.noneOf(Field.class);
        for (Field field : requested)
            if (!fields.add(field)) throw validation("fields", "DUPLICATE", "已映射字段不可重复");
        return fields;
    }

    private static ApplicationException validation(String field, String code, String message) {
        return ApplicationException.validation(new FieldViolation(field, code, message));
    }
}
