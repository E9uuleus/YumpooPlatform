package com.yumpoo.platform.operations.api;

import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.logging.LogQueryPort;
import com.yumpoo.platform.operations.application.OperationsAccessPolicy;
import com.yumpoo.platform.operations.application.OperationsAlertService;
import com.yumpoo.platform.operations.application.OperationsModels.Alert;
import com.yumpoo.platform.operations.application.OperationsModels.AlertDetail;
import com.yumpoo.platform.operations.application.OperationsModels.AlertSummary;
import com.yumpoo.platform.operations.application.OperationsModels.HostInfo;
import com.yumpoo.platform.operations.application.OperationsModels.MetricSeries;
import com.yumpoo.platform.operations.application.OperationsModels.Overview;
import com.yumpoo.platform.operations.application.OperationsModels.Page;
import com.yumpoo.platform.operations.application.OperationsModels.Rule;
import com.yumpoo.platform.operations.application.OperationsModels.RuleUpdate;
import com.yumpoo.platform.operations.application.OperationsModels.SessionSummary;
import com.yumpoo.platform.operations.application.OperationsModels.SessionUser;
import com.yumpoo.platform.operations.application.OperationsQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@ApiV1Controller
public class OperationsController {

    private final OperationsAccessPolicy access;
    private final OperationsQueryService queries;
    private final OperationsAlertService alerts;
    private final LogQueryPort logs;
    private final IfMatchParser etags;
    private final Clock clock;

    public OperationsController(
        OperationsAccessPolicy access,
        OperationsQueryService queries,
        OperationsAlertService alerts,
        LogQueryPort logs,
        IfMatchParser etags,
        Clock clock
    ) {
        this.access = access;
        this.queries = queries;
        this.alerts = alerts;
        this.logs = logs;
        this.etags = etags;
        this.clock = clock;
    }

    @GetMapping("/admin/operations/overview")
    public ResponseEntity<Overview> overview() {
        access.requireManager();
        return response(queries.overview());
    }

    @GetMapping("/admin/operations/host")
    public ResponseEntity<HostInfo> host() {
        access.requireManager();
        return response(queries.host());
    }

    @GetMapping("/admin/operations/metrics")
    public ResponseEntity<MetricSeries> metrics(
        @RequestParam(defaultValue = "1h") String range,
        @RequestParam(required = false) String keys
    ) {
        access.requireManager();
        return response(queries.metrics(range, csv(keys)));
    }

    @GetMapping("/admin/operations/sessions/summary")
    public ResponseEntity<SessionSummary> sessionSummary() {
        return response(queries.sessionSummary(access.requireManager().companyId()));
    }

    @GetMapping("/admin/operations/sessions")
    public ResponseEntity<Page<SessionUser>> sessions(
        @RequestParam(required = false) String presence,
        @RequestParam(required = false) String clientType,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return response(queries.sessions(access.requireManager().companyId(), presence, clientType, q, page, size));
    }

    @GetMapping("/admin/operations/logs")
    public ResponseEntity<LogQueryPort.Page> logs(
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to,
        @RequestParam(required = false) String levels,
        @RequestParam(required = false) String modules,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String requestId,
        @RequestParam(required = false) String event,
        @RequestParam(required = false) String userId,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "100") int limit
    ) {
        access.requireManager();
        Instant end = to == null ? clock.instant() : to;
        return response(
            logs.search(
                from == null ? end.minusSeconds(900) : from,
                end,
                filter(levels, modules, q, requestId, event, userId),
                cursor,
                limit
            )
        );
    }

    @GetMapping("/admin/operations/logs/tail")
    public ResponseEntity<LogQueryPort.Tail> tail(
        @RequestParam(required = false) String bootId,
        @RequestParam(required = false) Long afterSeq,
        @RequestParam(required = false) String levels,
        @RequestParam(required = false) String modules,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String requestId,
        @RequestParam(required = false) String event,
        @RequestParam(required = false) String userId,
        @RequestParam(defaultValue = "100") int limit
    ) {
        access.requireManager();
        return response(logs.tail(bootId, afterSeq, filter(levels, modules, q, requestId, event, userId), limit));
    }

    @GetMapping("/admin/operations/logs/histogram")
    public ResponseEntity<LogQueryPort.Histogram> histogram(
        @RequestParam Instant from,
        @RequestParam Instant to,
        @RequestParam(required = false) String levels,
        @RequestParam(required = false) String modules,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String requestId,
        @RequestParam(required = false) String event,
        @RequestParam(required = false) String userId
    ) {
        access.requireManager();
        return response(logs.histogram(from, to, filter(levels, modules, q, requestId, event, userId)));
    }

    @GetMapping("/admin/operations/alerts/summary")
    public ResponseEntity<AlertSummary> alertSummary() {
        access.requireManager();
        return response(alerts.summary());
    }

    @GetMapping("/admin/operations/alerts")
    public ResponseEntity<Page<Alert>> alerts(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String severity,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        access.requireManager();
        return response(alerts.list(status, severity, page, size));
    }

    @GetMapping("/admin/operations/alerts/{alertId}")
    public ResponseEntity<AlertDetail> alert(@PathVariable UUID alertId) {
        access.requireManager();
        return response(alerts.detail(alertId));
    }

    public record AcknowledgeRequest(@Size(max = 200) String note) {}

    @PostMapping("/admin/operations/alerts/{alertId}/acknowledge")
    public ResponseEntity<AlertDetail> acknowledge(
        @PathVariable UUID alertId,
        @Valid @RequestBody AcknowledgeRequest request
    ) {
        return response(alerts.acknowledge(access.requireManager(), alertId, request.note()));
    }

    @GetMapping("/admin/operations/alert-rules")
    public ResponseEntity<List<Rule>> rules() {
        access.requireManager();
        return response(alerts.rules());
    }

    public record RuleRequest(
        @NotNull Boolean enabled,
        Double warningThreshold,
        @NotNull Double criticalThreshold,
        @NotNull @Min(0) @Max(3600) Integer forSeconds
    ) {}

    @PutMapping("/admin/operations/alert-rules/{code}")
    public ResponseEntity<Rule> rule(
        @PathVariable String code,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @Valid @RequestBody RuleRequest request
    ) {
        var actor = access.requireManager();
        alerts.rule(code);
        long version = etags.parseForVisibleResource(true, ifMatch);
        Rule result = alerts.update(
            actor,
            code,
            version,
            new RuleUpdate(
                request.enabled(),
                request.warningThreshold(),
                request.criticalThreshold(),
                request.forSeconds()
            )
        );
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.etag()).body(result);
    }

    private static Set<String> csv(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return new LinkedHashSet<>(
            Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList()
        );
    }

    private static LogQueryPort.Filter filter(
        String levels,
        String modules,
        String q,
        String requestId,
        String event,
        String userId
    ) {
        return new LogQueryPort.Filter(levels == null ? null : csv(levels), csv(modules), q, requestId, event, userId);
    }

    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
