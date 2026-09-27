package com.yumpoo.platform.operations.application;

import com.yumpoo.platform.operations.application.OperationsModels.Alert;
import com.yumpoo.platform.operations.application.OperationsModels.AlertEvent;
import com.yumpoo.platform.operations.application.OperationsModels.MetricPoint;
import com.yumpoo.platform.operations.application.OperationsModels.Page;
import com.yumpoo.platform.operations.application.OperationsModels.Rule;
import com.yumpoo.platform.operations.application.OperationsModels.RuleUpdate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface OperationsRepository {
    void saveMinute(UUID bootId, Instant startedAt, Instant minute, Map<String, Double> metrics, int count);
    List<MetricPoint> metrics(Instant from, Instant to);
    List<Instant> restarts(Instant from, Instant to);
    void cleanup(Instant now);
    List<Rule> rules();
    Rule lockRule(String code);
    Rule updateRule(String code, long expected, RuleUpdate update, UUID actor, Instant now);
    List<Alert> firing();
    Optional<Alert> findAlert(UUID id, boolean lock);
    Page<Alert> alerts(String status, String severity, int page, int size);
    List<AlertEvent> events(UUID id);
    void createAlert(Alert alert);
    void updateAlert(Alert alert);
    void event(UUID id, String event, String severity, Double value, UUID actor, Instant now);
}
