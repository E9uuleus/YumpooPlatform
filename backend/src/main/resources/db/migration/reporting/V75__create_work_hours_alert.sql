CREATE TABLE yumpoo.work_hours_alert_setting (
    company_id uuid PRIMARY KEY REFERENCES yumpoo.company (id),
    daily_threshold_minutes integer NOT NULL,
    row_version bigint NOT NULL,
    updated_by_user_id uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_work_hours_alert_setting_threshold CHECK (daily_threshold_minutes BETWEEN 60 AND 1440),
    CONSTRAINT ck_work_hours_alert_setting_row_version CHECK (row_version >= 1)
);
CREATE TABLE yumpoo.work_hours_alert (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES yumpoo.company (id),
    user_id uuid NOT NULL,
    work_date date NOT NULL,
    total_duration_ms bigint NOT NULL,
    threshold_minutes integer NOT NULL,
    detected_at timestamptz NOT NULL,
    CONSTRAINT uq_work_hours_alert_member_day UNIQUE (company_id, user_id, work_date)
);
