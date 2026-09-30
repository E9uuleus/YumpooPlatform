package com.yumpoo.platform.operations.infrastructure;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.operations.application.MetricAggregation;
import com.yumpoo.platform.operations.application.OperationsModels.Alert;
import com.yumpoo.platform.operations.application.OperationsModels.AlertEvent;
import com.yumpoo.platform.operations.application.OperationsModels.MetricPoint;
import com.yumpoo.platform.operations.application.OperationsModels.Page;
import com.yumpoo.platform.operations.application.OperationsModels.Rule;
import com.yumpoo.platform.operations.application.OperationsModels.RuleUpdate;
import com.yumpoo.platform.operations.application.OperationsRepository;
import com.yumpoo.platform.operations.domain.AlertRuleType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcOperationsRepository implements OperationsRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcOperationsRepository(DataSource source, ObjectMapper json) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(3);
        this.json = json;
    }

    @Override
    public void saveMinute(UUID bootId, Instant startedAt, Instant minute, Map<String, Double> metrics, int count) {
        jdbc.update(
            "INSERT INTO yumpoo.ops_process_run(boot_id,started_at) VALUES (?,?) ON CONFLICT DO NOTHING",
            bootId,
            time(startedAt)
        );
        jdbc.update(
            """
            INSERT INTO yumpoo.ops_metric_minute(boot_id,bucket_start,sample_count,metrics) VALUES (?,?,?,?::jsonb)
            ON CONFLICT(boot_id,bucket_start) DO UPDATE SET sample_count=EXCLUDED.sample_count,metrics=EXCLUDED.metrics
            """,
            bootId,
            time(minute),
            count,
            json.writeValueAsString(metrics)
        );
    }

    @Override
    public List<MetricPoint> metrics(Instant from, Instant to) {
        List<MetricPoint> points = jdbc.query(
            "SELECT bucket_start,metrics FROM yumpoo.ops_metric_minute WHERE bucket_start>=? AND bucket_start<? ORDER BY bucket_start,boot_id",
            (rs, n) ->
                new MetricPoint(
                    rs.getTimestamp(1).toInstant(),
                    json.readValue(rs.getString(2), new TypeReference<Map<String, Double>>() {})
                ),
            time(from),
            time(to)
        );
        Map<Instant, List<MetricPoint>> grouped = new TreeMap<>();
        points.forEach(p -> grouped.computeIfAbsent(p.at(), ignored -> new ArrayList<>()).add(p));
        return grouped
            .entrySet()
            .stream()
            .map(e -> new MetricPoint(e.getKey(), MetricAggregation.aggregate(e.getValue())))
            .toList();
    }

    @Override
    public List<Instant> restarts(Instant from, Instant to) {
        return jdbc.query(
            "SELECT started_at FROM yumpoo.ops_process_run WHERE started_at>=? AND started_at<? ORDER BY started_at",
            (rs, n) -> rs.getTimestamp(1).toInstant(),
            time(from),
            time(to)
        );
    }

    @Override
    public void cleanup(Instant now) {
        jdbc.update("DELETE FROM yumpoo.ops_metric_minute WHERE bucket_start<?", time(now.minusSeconds(14 * 86400L)));
        jdbc.update(
            "DELETE FROM yumpoo.ops_process_run r WHERE started_at<? AND NOT EXISTS(SELECT 1 FROM yumpoo.ops_metric_minute m WHERE m.boot_id=r.boot_id)",
            time(now.minusSeconds(14 * 86400L))
        );
        jdbc.update(
            "DELETE FROM yumpoo.ops_alert WHERE status='RESOLVED' AND resolved_at<?",
            time(now.minusSeconds(180 * 86400L))
        );
    }

    @Override
    public List<Rule> rules() {
        return jdbc.query("SELECT * FROM yumpoo.ops_alert_rule ORDER BY code", this::rule);
    }

    @Override
    public Rule lockRule(String code) {
        return jdbc
            .query("SELECT * FROM yumpoo.ops_alert_rule WHERE code=? FOR UPDATE", this::rule, code)
            .stream()
            .findFirst()
            .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    @Override
    public Rule updateRule(String code, long expected, RuleUpdate update, UUID actor, Instant now) {
        int rows = jdbc.update(
            """
            UPDATE yumpoo.ops_alert_rule SET enabled=?,warning_threshold=?,critical_threshold=?,for_seconds=?,row_version=row_version+1,updated_at=?,updated_by_user_id=?
            WHERE code=? AND row_version=?
            """,
            update.enabled(),
            update.warningThreshold(),
            update.criticalThreshold(),
            update.forSeconds(),
            time(now),
            actor,
            code,
            expected
        );
        if (rows != 1) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        return lockRule(code);
    }

    private Rule rule(ResultSet rs, int n) throws SQLException {
        String code = rs.getString("code");
        var type = AlertRuleType.valueOf(code);
        long version = rs.getLong("row_version");
        return new Rule(
            code,
            rs.getBoolean("enabled"),
            number(rs, "warning_threshold"),
            number(rs, "critical_threshold"),
            rs.getInt("for_seconds"),
            version,
            "\"" + version + "\"",
            type.below ? "BELOW" : "ABOVE",
            type.unit
        );
    }

    @Override
    public List<Alert> firing() {
        return jdbc.query(
            "SELECT * FROM yumpoo.ops_alert WHERE status='FIRING' ORDER BY severity,started_at DESC,id",
            this::alert
        );
    }

    @Override
    public Optional<Alert> findAlert(UUID id, boolean lock) {
        return jdbc
            .query("SELECT * FROM yumpoo.ops_alert WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::alert, id)
            .stream()
            .findFirst();
    }

    @Override
    public Page<Alert> alerts(String status, String severity, int page, int size) {
        String where = " WHERE (?::varchar IS NULL OR status=?) AND (?::varchar IS NULL OR severity=?)";
        long count = Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM yumpoo.ops_alert" + where,
                Long.class,
                status,
                status,
                severity,
                severity
            )
        );
        var items = jdbc.query(
            "SELECT * FROM yumpoo.ops_alert" + where + " ORDER BY severity,started_at DESC,id LIMIT ? OFFSET ?",
            this::alert,
            status,
            status,
            severity,
            severity,
            size,
            (long) page * size
        );
        return new Page<>(items, count, page, size);
    }

    @Override
    public List<AlertEvent> events(UUID id) {
        return jdbc.query(
            "SELECT * FROM yumpoo.ops_alert_event WHERE alert_id=? ORDER BY occurred_at,id",
            (rs, n) ->
                new AlertEvent(
                    rs.getObject("id", UUID.class),
                    rs.getString("event_type"),
                    rs.getString("severity"),
                    number(rs, "value"),
                    rs.getObject("actor_user_id", UUID.class),
                    instant(rs, "occurred_at")
                ),
            id
        );
    }

    @Override
    public void createAlert(Alert alert) {
        jdbc.update(
            """
            INSERT INTO yumpoo.ops_alert(id,rule_code,subject_key,severity,status,started_at,evaluated_at,peak_value,last_value,params)
            VALUES (?,?,?,?,'FIRING',?,?,?,?,?::jsonb)
            """,
            alert.id(),
            alert.ruleCode(),
            alert.subjectKey(),
            alert.severity(),
            time(alert.startedAt()),
            time(alert.evaluatedAt()),
            alert.peakValue(),
            alert.lastValue(),
            json.writeValueAsString(alert.params())
        );
    }

    @Override
    public void updateAlert(Alert alert) {
        jdbc.update(
            """
            UPDATE yumpoo.ops_alert SET severity=?,status=?,evaluated_at=?,peak_value=?,last_value=?,acknowledged_at=?,
            acknowledged_by_user_id=?,acknowledge_note=?,resolved_at=?,resolution=? WHERE id=?
            """,
            alert.severity(),
            alert.status(),
            time(alert.evaluatedAt()),
            alert.peakValue(),
            alert.lastValue(),
            time(alert.acknowledgedAt()),
            alert.acknowledgedByUserId(),
            alert.acknowledgeNote(),
            time(alert.resolvedAt()),
            alert.resolution(),
            alert.id()
        );
    }

    @Override
    public void event(UUID id, String event, String severity, Double value, UUID actor, Instant now) {
        jdbc.update(
            "INSERT INTO yumpoo.ops_alert_event(id,alert_id,event_type,severity,value,actor_user_id,occurred_at) VALUES (?,?,?,?,?,?,?)",
            UUID.randomUUID(),
            id,
            event,
            severity,
            value,
            actor,
            time(now)
        );
    }

    private Alert alert(ResultSet rs, int n) throws SQLException {
        return new Alert(
            rs.getObject("id", UUID.class),
            rs.getString("rule_code"),
            rs.getString("subject_key"),
            rs.getString("severity"),
            rs.getString("status"),
            instant(rs, "started_at"),
            instant(rs, "evaluated_at"),
            number(rs, "peak_value"),
            number(rs, "last_value"),
            json.readValue(rs.getString("params"), new TypeReference<Map<String, Object>>() {}),
            instant(rs, "acknowledged_at"),
            rs.getObject("acknowledged_by_user_id", UUID.class),
            rs.getString("acknowledge_note"),
            instant(rs, "resolved_at"),
            rs.getString("resolution")
        );
    }

    private static Double number(ResultSet rs, String name) throws SQLException {
        var value = rs.getBigDecimal(name);
        return value == null ? null : value.doubleValue();
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp time(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
