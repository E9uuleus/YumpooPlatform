CREATE TABLE yumpoo.work_item_table_settings (
    company_id uuid NOT NULL REFERENCES yumpoo.company(id),
    project_id uuid NOT NULL,
    user_id uuid NOT NULL,
    settings jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_work_item_table_settings PRIMARY KEY (company_id, project_id, user_id),
    CONSTRAINT ck_work_item_table_settings_object CHECK (jsonb_typeof(settings) = 'object')
);
