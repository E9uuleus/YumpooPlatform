CREATE TABLE yumpoo.ops_alert_rule (
    code varchar(48) PRIMARY KEY,
    enabled boolean NOT NULL DEFAULT true,
    warning_threshold numeric(18,4),
    critical_threshold numeric(18,4) NOT NULL,
    for_seconds integer NOT NULL CHECK (for_seconds BETWEEN 0 AND 3600 AND for_seconds % 15 = 0),
    row_version bigint NOT NULL DEFAULT 1 CHECK (row_version >= 1),
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by_user_id uuid,
    CONSTRAINT ck_ops_alert_rule_threshold CHECK (critical_threshold > 0 AND (warning_threshold IS NULL OR warning_threshold > 0))
);
INSERT INTO yumpoo.ops_alert_rule(code,warning_threshold,critical_threshold,for_seconds) VALUES
('HOST_CPU_HIGH',0.85,0.95,300),('JVM_HEAP_HIGH',0.85,0.95,300),('DISK_LOW',0.20,0.10,60),
('DB_UNAVAILABLE',NULL,2,0),('DB_SLOW',200,1000,300),('DB_POOL_SATURATED',1,5,120),
('HTTP_ERROR_RATE',0.01,0.05,300),('HTTP_LATENCY_P95',1500,3000,300),
('OUTBOX_BACKLOG_AGE',300,900,60),('OUTBOX_DEAD',1,10,0),('ERROR_LOG_BURST',10,50,0),('CONFIG_POSTURE',NULL,1,0);

CREATE TABLE yumpoo.ops_alert (
    id uuid PRIMARY KEY,
    rule_code varchar(48) NOT NULL REFERENCES yumpoo.ops_alert_rule(code),
    subject_key varchar(128) NOT NULL,
    severity varchar(16) NOT NULL CHECK (severity IN ('WARNING','CRITICAL')),
    status varchar(16) NOT NULL CHECK (status IN ('FIRING','RESOLVED')),
    started_at timestamptz NOT NULL,
    evaluated_at timestamptz NOT NULL,
    peak_value numeric(18,4),
    last_value numeric(18,4),
    params jsonb NOT NULL CHECK (jsonb_typeof(params)='object'),
    acknowledged_at timestamptz,
    acknowledged_by_user_id uuid,
    acknowledge_note varchar(200),
    resolved_at timestamptz,
    resolution varchar(24),
    CONSTRAINT ck_ops_alert_resolution CHECK (
        (status='FIRING' AND resolved_at IS NULL AND resolution IS NULL)
        OR (status='RESOLVED' AND resolved_at>=started_at AND resolution IN ('RECOVERED','RULE_DISABLED'))),
    CONSTRAINT ck_ops_alert_ack CHECK ((acknowledged_at IS NULL)=(acknowledged_by_user_id IS NULL)
        AND (acknowledged_at IS NOT NULL OR acknowledge_note IS NULL))
);
CREATE UNIQUE INDEX uq_ops_alert_firing ON yumpoo.ops_alert(rule_code,subject_key) WHERE status='FIRING';
CREATE INDEX ix_ops_alert_started ON yumpoo.ops_alert(status,started_at DESC,id);

CREATE TABLE yumpoo.ops_alert_event (
    id uuid PRIMARY KEY,
    alert_id uuid NOT NULL REFERENCES yumpoo.ops_alert(id) ON DELETE CASCADE,
    event_type varchar(16) NOT NULL CHECK (event_type IN ('FIRED','ESCALATED','DEESCALATED','ACKNOWLEDGED','RESOLVED')),
    severity varchar(16) NOT NULL CHECK (severity IN ('WARNING','CRITICAL')),
    value numeric(18,4),
    actor_user_id uuid,
    occurred_at timestamptz NOT NULL
);
CREATE INDEX ix_ops_alert_event_alert ON yumpoo.ops_alert_event(alert_id,occurred_at,id);
