ALTER TABLE yumpoo.company_calendar_day DROP CONSTRAINT ck_company_calendar_day_type;
ALTER TABLE yumpoo.company_calendar_day ADD CONSTRAINT ck_company_calendar_day_type CHECK (
    day_type IN ('WORKDAY', 'NON_WORKDAY', 'HOLIDAY')
);
ALTER TABLE yumpoo.company_calendar_day DROP CONSTRAINT ck_company_calendar_day_minutes;
ALTER TABLE yumpoo.company_calendar_day ADD CONSTRAINT ck_company_calendar_day_minutes CHECK (
    (day_type = 'WORKDAY' AND (standard_minutes IS NULL OR standard_minutes BETWEEN 1 AND 720))
    OR (day_type IN ('NON_WORKDAY', 'HOLIDAY') AND standard_minutes IS NOT NULL AND standard_minutes = 0)
);

CREATE TABLE yumpoo.company_calendar_year (
    company_id uuid NOT NULL REFERENCES yumpoo.company (id),
    calendar_year integer NOT NULL,
    row_version bigint NOT NULL,
    updated_by_user_id uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT pk_company_calendar_year PRIMARY KEY (company_id, calendar_year),
    CONSTRAINT ck_company_calendar_year_row_version CHECK (row_version >= 1)
);
