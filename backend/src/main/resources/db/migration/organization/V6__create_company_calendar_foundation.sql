CREATE TABLE yumpoo.company (
    id uuid PRIMARY KEY,
    singleton_slot smallint NOT NULL DEFAULT 1,
    display_name varchar(200) NOT NULL,
    timezone varchar(100) NOT NULL,
    week_start_day varchar(16) NOT NULL DEFAULT 'MONDAY',
    default_workday_minutes integer NOT NULL DEFAULT 480,
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_company_singleton_slot UNIQUE (singleton_slot),
    CONSTRAINT ck_company_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_company_singleton_slot CHECK (singleton_slot = 1),
    CONSTRAINT ck_company_display_name CHECK (
        display_name = btrim(display_name) AND length(display_name) BETWEEN 1 AND 200
    ),
    CONSTRAINT ck_company_timezone CHECK (
        timezone = btrim(timezone) AND length(timezone) BETWEEN 1 AND 100
    ),
    CONSTRAINT ck_company_week_start_day CHECK (week_start_day = 'MONDAY'),
    CONSTRAINT ck_company_default_workday_minutes CHECK (
        default_workday_minutes BETWEEN 1 AND 720
    ),
    CONSTRAINT ck_company_row_version CHECK (row_version >= 0),
    CONSTRAINT ck_company_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE yumpoo.company_calendar_day (
    company_id uuid NOT NULL,
    calendar_date date NOT NULL,
    day_type varchar(20) NOT NULL,
    standard_minutes integer NULL,
    source varchar(20) NOT NULL,
    note varchar(500) NULL,
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT company_calendar_day_pkey PRIMARY KEY (company_id, calendar_date),
    CONSTRAINT fk_company_calendar_day_company FOREIGN KEY (company_id)
        REFERENCES yumpoo.company (id),
    CONSTRAINT ck_company_calendar_day_type CHECK (
        day_type IN ('WORKDAY', 'NON_WORKDAY')
    ),
    CONSTRAINT ck_company_calendar_day_minutes CHECK (
        (day_type = 'WORKDAY' AND (standard_minutes IS NULL OR standard_minutes BETWEEN 1 AND 720))
        OR (day_type = 'NON_WORKDAY' AND standard_minutes IS NOT NULL AND standard_minutes = 0)
    ),
    CONSTRAINT ck_company_calendar_day_source CHECK (
        source IN ('MANUAL', 'IMPORT')
    ),
    CONSTRAINT ck_company_calendar_day_note CHECK (
        note IS NULL OR (note = btrim(note) AND length(note) BETWEEN 1 AND 500)
    ),
    CONSTRAINT ck_company_calendar_day_row_version CHECK (row_version >= 0),
    CONSTRAINT ck_company_calendar_day_timestamps CHECK (updated_at >= created_at)
);

INSERT INTO yumpoo.company (
    id,
    singleton_slot,
    display_name,
    timezone,
    week_start_day,
    default_workday_minutes,
    row_version,
    created_at,
    updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000001',
    1,
    'Yumpoo',
    'Asia/Shanghai',
    'MONDAY',
    480,
    0,
    transaction_timestamp(),
    transaction_timestamp()
);

COMMENT ON TABLE yumpoo.company IS
    'Single Company configuration root for the first release';
COMMENT ON COLUMN yumpoo.company.timezone IS
    'Runtime tzdb-recognized IANA Zone ID; validated by the application';
COMMENT ON TABLE yumpoo.company_calendar_day IS
    'Explicit Company workday overrides; absent dates use weekday defaults';
COMMENT ON COLUMN yumpoo.company_calendar_day.standard_minutes IS
    'WORKDAY null inherits Company default; NON_WORKDAY is always zero';
