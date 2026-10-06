CREATE TABLE yumpoo.project_notification_preference (
    company_id uuid NOT NULL REFERENCES yumpoo.company(id),
    project_id uuid NOT NULL,
    user_id uuid NOT NULL,
    mode varchar(16) NOT NULL,
    notify_mention boolean NOT NULL,
    notify_comment boolean NOT NULL,
    notify_assigned boolean NOT NULL,
    notify_connection_created boolean NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_project_notification_preference PRIMARY KEY (company_id, project_id, user_id),
    CONSTRAINT ck_project_notification_preference_mode CHECK (mode IN ('ALL','MUTED','CUSTOM'))
);
