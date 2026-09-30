package com.yumpoo.platform.operations.application;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.operations.application.OperationsModels.Alert;
import com.yumpoo.platform.operations.application.OperationsModels.AlertDetail;
import com.yumpoo.platform.operations.application.OperationsModels.AlertSummary;
import com.yumpoo.platform.operations.application.OperationsModels.Page;
import com.yumpoo.platform.operations.application.OperationsModels.PostureCheck;
import com.yumpoo.platform.operations.application.OperationsModels.Rule;
import com.yumpoo.platform.operations.application.OperationsModels.RuleUpdate;
import com.yumpoo.platform.operations.domain.AlertEvaluator;
import com.yumpoo.platform.operations.domain.AlertRuleType;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Service
public class OperationsAlertService {

    private final OperationsRepository repository;
    private final SecurityAuditAppendPort audit;
    private final ObjectMapper json;
    private final Clock clock;
    private volatile AlertEvaluator evaluator = new AlertEvaluator();
    private volatile List<Alert> active = List.of();

    public OperationsAlertService(
        OperationsRepository repository,
        SecurityAuditAppendPort audit,
        ObjectMapper json,
        Clock clock
    ) {
        this.repository = repository;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public List<Alert> cached() {
        return active;
    }

    @Transactional
    public void recordDatabaseOutage(Instant detectedAt, int failures) {
        Rule rule = repository.lockRule("DB_UNAVAILABLE");
        if (!rule.enabled() || failures < rule.criticalThreshold()) return;
        if (
            repository
                .firing()
                .stream()
                .anyMatch(a -> a.ruleCode().equals(rule.code()))
        ) return;
        Alert alert = new Alert(
            UUID.randomUUID(),
            rule.code(),
            "instance",
            "CRITICAL",
            "FIRING",
            detectedAt,
            detectedAt,
            (double) failures,
            (double) failures,
            parameters(rule),
            null,
            null,
            null,
            null,
            null
        );
        repository.createAlert(alert);
        repository.event(alert.id(), "FIRED", "CRITICAL", (double) failures, null, detectedAt);
        publishActive();
    }

    public List<Rule> rules() {
        return repository.rules();
    }

    public Rule rule(String code) {
        return rules()
            .stream()
            .filter(r -> r.code().equals(code))
            .findFirst()
            .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    public Page<Alert> list(String status, String severity, int page, int size) {
        validatePage(page, size);
        if (
            (status != null && !Set.of("FIRING", "RESOLVED").contains(status)) ||
            (severity != null && !Set.of("WARNING", "CRITICAL").contains(severity))
        ) throw invalid();
        return repository.alerts(status, severity, page, size);
    }

    public AlertDetail detail(UUID id) {
        return new AlertDetail(
            repository
                .findAlert(id, false)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND)),
            repository.events(id)
        );
    }

    public AlertSummary summary() {
        var alerts = repository.firing();
        return new AlertSummary(
            alerts
                .stream()
                .filter(a -> a.acknowledgedAt() == null && a.severity().equals("WARNING"))
                .count(),
            alerts
                .stream()
                .filter(a -> a.acknowledgedAt() == null && a.severity().equals("CRITICAL"))
                .count()
        );
    }

    @Transactional
    public Rule update(CurrentActor actor, String code, long expected, RuleUpdate update) {
        Rule before = repository.lockRule(code);
        if (
            !AlertRuleType.valueOf(code).valid(
                update.warningThreshold(),
                update.criticalThreshold(),
                update.forSeconds()
            )
        ) throw ApplicationException.validation(
            new FieldViolation("threshold", "INVALID", "阈值或持续时间不符合规则要求")
        );
        Rule after = repository.updateRule(code, expected, update, actor.userId(), clock.instant());
        audit.append(
            new SecurityAuditDraft(
                actor.companyId(),
                "ops-rule:" + code + ":" + after.rowVersion(),
                "OPS_ALERT_RULE_UPDATED",
                SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.user(
                    actor.userId(),
                    actor.platformRoles().stream().map(Enum::name).collect(Collectors.toSet())
                ),
                "OPS_ALERT_RULE",
                code,
                null,
                json.valueToTree(fields(before)),
                json.valueToTree(fields(after)),
                null,
                null,
                null,
                null,
                clock.instant()
            )
        );
        if (!after.enabled()) for (Alert alert : repository.firing())
            if (alert.ruleCode().equals(code)) {
                Alert locked = repository.findAlert(alert.id(), true).orElseThrow();
                resolve(locked, "RULE_DISABLED", clock.instant());
            }
        publishActive();
        return after;
    }

    private Map<String, Object> fields(Rule rule) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("enabled", rule.enabled());
        fields.put("warningThreshold", rule.warningThreshold());
        fields.put("criticalThreshold", rule.criticalThreshold());
        fields.put("forSeconds", rule.forSeconds());
        return fields;
    }

    @Transactional
    public AlertDetail acknowledge(CurrentActor actor, UUID id, String note) {
        if (note != null && note.length() > 200) throw invalid();
        Alert candidate = repository
            .findAlert(id, false)
            .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        repository.lockRule(candidate.ruleCode());
        Alert alert = repository.findAlert(id, true).orElseThrow();
        if (alert.acknowledgedAt() == null) {
            if (!alert.status().equals("FIRING")) throw new ApplicationException(
                StandardErrorCode.INVALID_STATE_TRANSITION
            );
            Instant now = clock.instant();
            repository.updateAlert(
                new Alert(
                    alert.id(),
                    alert.ruleCode(),
                    alert.subjectKey(),
                    alert.severity(),
                    alert.status(),
                    alert.startedAt(),
                    alert.evaluatedAt(),
                    alert.peakValue(),
                    alert.lastValue(),
                    alert.params(),
                    now,
                    actor.userId(),
                    note == null || note.isBlank() ? null : note.strip(),
                    null,
                    null
                )
            );
            repository.event(id, "ACKNOWLEDGED", alert.severity(), alert.lastValue(), actor.userId(), now);
        }
        publishActive();
        return detail(id);
    }

    @Transactional
    public void evaluate(Map<String, Double> values, List<PostureCheck> posture, Instant now) {
        AlertEvaluator working = evaluator.copy();
        for (Rule candidate : repository.rules()) {
            Rule rule = repository.lockRule(candidate.code());
            var type = AlertRuleType.valueOf(rule.code());
            Map<String, Double> subjects = new LinkedHashMap<>();
            if (type == AlertRuleType.DISK_LOW) values.forEach((key, free) -> {
                if (key.startsWith("disk.") && key.endsWith(".free")) {
                    String subject = key.substring(5, key.length() - 5);
                    Double total = values.get("disk." + subject + ".total");
                    subjects.put(subject, free == null || total == null || total <= 0 ? null : free / total);
                }
            });
            else if (type == AlertRuleType.CONFIG_POSTURE) posture.forEach(check ->
                subjects.put(
                    check.code(),
                    check.status().equals("UNKNOWN")
                        ? null
                        : check.severity().equals("CRITICAL") && check.status().equals("FAIL")
                          ? 1d
                          : 0d
                )
            );
            else subjects.put("instance", values.get(type.metric));
            List<Alert> firing = repository
                .firing()
                .stream()
                .filter(a -> a.ruleCode().equals(rule.code()))
                .toList();
            firing.forEach(a -> subjects.putIfAbsent(a.subjectKey(), null));
            for (var subject : subjects.entrySet()) {
                Alert existing = firing
                    .stream()
                    .filter(a -> a.subjectKey().equals(subject.getKey()))
                    .findFirst()
                    .orElse(null);
                if (existing != null) existing = repository.findAlert(existing.id(), true).orElseThrow();
                Double value = subject.getValue();
                var decision = working.evaluate(
                    rule.code() + ":" + subject.getKey(),
                    rule.rowVersion(),
                    type,
                    rule.enabled(),
                    rule.warningThreshold(),
                    rule.criticalThreshold(),
                    rule.forSeconds(),
                    value,
                    existing == null ? null : existing.severity(),
                    now
                );
                if (decision != null && decision.event().equals("FIRED")) {
                    Alert created = new Alert(
                        UUID.randomUUID(),
                        rule.code(),
                        subject.getKey(),
                        decision.severity(),
                        "FIRING",
                        now,
                        now,
                        value,
                        value,
                        parameters(rule),
                        null,
                        null,
                        null,
                        null,
                        null
                    );
                    repository.createAlert(created);
                    repository.event(created.id(), "FIRED", created.severity(), value, null, now);
                } else if (existing != null) {
                    if (decision != null && Set.of("RESOLVED", "RULE_DISABLED").contains(decision.event())) {
                        resolve(
                            existing,
                            decision.event().equals("RULE_DISABLED") ? "RULE_DISABLED" : "RECOVERED",
                            now
                        );
                    } else if (value != null) {
                        Double peak =
                            existing.peakValue() == null
                                ? value
                                : type.below
                                  ? Math.min(value, existing.peakValue())
                                  : Math.max(value, existing.peakValue());
                        boolean escalated = decision != null && decision.event().equals("ESCALATED");
                        String severity = decision == null ? existing.severity() : decision.severity();
                        repository.updateAlert(
                            new Alert(
                                existing.id(),
                                existing.ruleCode(),
                                existing.subjectKey(),
                                severity,
                                "FIRING",
                                existing.startedAt(),
                                now,
                                peak,
                                value,
                                existing.params(),
                                escalated ? null : existing.acknowledgedAt(),
                                escalated ? null : existing.acknowledgedByUserId(),
                                escalated ? null : existing.acknowledgeNote(),
                                null,
                                null
                            )
                        );
                        if (decision != null) repository.event(
                            existing.id(),
                            decision.event(),
                            severity,
                            value,
                            null,
                            now
                        );
                    }
                }
            }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        evaluator = working;
                    }
                }
            );
        } else evaluator = working;
        publishActive();
    }

    private Map<String, Object> parameters(Rule rule) {
        Map<String, Object> params = new LinkedHashMap<>(fields(rule));
        params.put("unit", rule.unit());
        params.put("comparison", rule.comparison());
        params.put("metric", AlertRuleType.valueOf(rule.code()).metric);
        params.put("ruleVersion", rule.rowVersion());
        return params;
    }

    private void publishActive() {
        List<Alert> snapshot = List.copyOf(repository.firing());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        active = snapshot;
                    }
                }
            );
        } else active = snapshot;
    }

    private void resolve(Alert alert, String reason, Instant now) {
        repository.updateAlert(
            new Alert(
                alert.id(),
                alert.ruleCode(),
                alert.subjectKey(),
                alert.severity(),
                "RESOLVED",
                alert.startedAt(),
                now,
                alert.peakValue(),
                alert.lastValue(),
                alert.params(),
                alert.acknowledgedAt(),
                alert.acknowledgedByUserId(),
                alert.acknowledgeNote(),
                now,
                reason
            )
        );
        repository.event(alert.id(), "RESOLVED", alert.severity(), alert.lastValue(), null, now);
    }

    public static void validatePage(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) throw invalid();
    }

    private static ApplicationException invalid() {
        return new ApplicationException(StandardErrorCode.VALIDATION_FAILED);
    }
}
