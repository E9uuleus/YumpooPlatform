CREATE TABLE yumpoo.scheduled_job (
    company_id uuid NOT NULL,
    job_code varchar(64) NOT NULL,
    enabled boolean NOT NULL,
    cron varchar(64) NOT NULL,
    zone_id varchar(100) NOT NULL,
    next_run_at timestamptz,
    last_started_at timestamptz,
    last_finished_at timestamptz,
    last_outcome varchar(16),
    last_error_code varchar(96),
    last_request_id varchar(64),
    updated_by_user_id uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    row_version bigint NOT NULL,
    CONSTRAINT pk_scheduled_job PRIMARY KEY (company_id, job_code),
    CONSTRAINT ck_scheduled_job_outcome CHECK (last_outcome IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_scheduled_job_row_version CHECK (row_version >= 1)
);
