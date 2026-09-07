CREATE TABLE yumpoo.work_item_timer_state (
    company_id uuid NOT NULL,
    user_id uuid NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (company_id, user_id),
    FOREIGN KEY (user_id, company_id) REFERENCES yumpoo.identity_user (id, company_id)
);

CREATE TABLE yumpoo.work_item_time_session (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    project_id uuid NOT NULL,
    work_item_id uuid NOT NULL REFERENCES yumpoo.work_item (id),
    user_id uuid NOT NULL,
    started_at timestamptz NOT NULL,
    stopped_at timestamptz,
    source varchar(16) NOT NULL CHECK (source IN ('TIMER', 'MANUAL')),
    row_version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz,
    change_reason varchar(500),
    CHECK (stopped_at IS NULL OR stopped_at >= started_at),
    CHECK (source <> 'MANUAL' OR stopped_at IS NOT NULL),
    CHECK (deleted_at IS NULL OR stopped_at IS NOT NULL),
    FOREIGN KEY (project_id, company_id) REFERENCES yumpoo.project (id, company_id),
    FOREIGN KEY (user_id, company_id) REFERENCES yumpoo.identity_user (id, company_id)
);
CREATE UNIQUE INDEX uq_work_item_running_timer
    ON yumpoo.work_item_time_session (company_id, user_id)
    WHERE stopped_at IS NULL AND deleted_at IS NULL;
CREATE INDEX ix_work_item_time_history ON yumpoo.work_item_time_session
    (company_id, work_item_id, started_at DESC, id) WHERE deleted_at IS NULL;
CREATE INDEX ix_work_item_time_overlap ON yumpoo.work_item_time_session
    (company_id, user_id, started_at, stopped_at) WHERE deleted_at IS NULL;
CREATE INDEX ix_project_time_sessions ON yumpoo.work_item_time_session
    (company_id, project_id, work_item_id) WHERE deleted_at IS NULL;

CREATE TABLE yumpoo.work_item_time_revision (
    company_id uuid NOT NULL,
    project_id uuid NOT NULL,
    revision bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (company_id, project_id),
    FOREIGN KEY (project_id, company_id) REFERENCES yumpoo.project (id, company_id)
);
