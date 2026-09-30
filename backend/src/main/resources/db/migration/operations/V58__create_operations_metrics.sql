CREATE TABLE yumpoo.ops_process_run (
    boot_id uuid PRIMARY KEY,
    started_at timestamptz NOT NULL
);
CREATE INDEX ix_ops_process_run_started ON yumpoo.ops_process_run (started_at);

CREATE TABLE yumpoo.ops_metric_minute (
    boot_id uuid NOT NULL REFERENCES yumpoo.ops_process_run(boot_id),
    bucket_start timestamptz NOT NULL,
    sample_count smallint NOT NULL CHECK (sample_count BETWEEN 1 AND 60),
    metrics jsonb NOT NULL CHECK (jsonb_typeof(metrics) = 'object'),
    PRIMARY KEY (boot_id, bucket_start),
    CONSTRAINT ck_ops_metric_minute_bucket CHECK (date_trunc('minute', bucket_start) = bucket_start)
);
CREATE INDEX ix_ops_metric_minute_time ON yumpoo.ops_metric_minute (bucket_start);
