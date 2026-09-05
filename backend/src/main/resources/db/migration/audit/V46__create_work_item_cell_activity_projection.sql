ALTER TABLE yumpoo.activity_projection_state
    DROP CONSTRAINT ck_activity_projection_state_code;

ALTER TABLE yumpoo.activity_projection_state
    ADD CONSTRAINT ck_activity_projection_state_code CHECK (
        projection_code IN ('ACTIVITY_V1', 'WORK_ITEM_CELL_ACTIVITY_V1')
    );

INSERT INTO yumpoo.activity_projection_state (projection_code, accepted_from)
VALUES ('WORK_ITEM_CELL_ACTIVITY_V1', transaction_timestamp());

CREATE TABLE yumpoo.work_item_cell_activity (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL,
    projection_code varchar(40) NOT NULL DEFAULT 'WORK_ITEM_CELL_ACTIVITY_V1',
    company_id uuid NOT NULL,
    project_id uuid NOT NULL,
    work_item_id uuid NOT NULL,
    content_id uuid NOT NULL,
    content_display_name varchar(80) NOT NULL,
    event_type varchar(128) NOT NULL,
    column_code varchar(32) NOT NULL,
    change_type varchar(16) NOT NULL,
    before_value jsonb,
    after_value jsonb,
    actor_type varchar(24) NOT NULL,
    actor_user_id uuid,
    actor_system_code varchar(80),
    actor_display_name varchar(200) NOT NULL,
    occurred_at timestamptz NOT NULL,
    projected_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    request_id varchar(64) NOT NULL,
    correlation_id varchar(64) NOT NULL,
    CONSTRAINT fk_work_item_cell_activity_company
        FOREIGN KEY (company_id) REFERENCES yumpoo.company (id),
    CONSTRAINT uq_work_item_cell_activity_projection
        UNIQUE (event_id, projection_code, column_code),
    CONSTRAINT ck_work_item_cell_activity_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_work_item_cell_activity_projection CHECK (
        projection_code = 'WORK_ITEM_CELL_ACTIVITY_V1'
    ),
    CONSTRAINT ck_work_item_cell_activity_column CHECK (
        column_code IN ('WORK_ITEM_NAME', 'ASSIGNEE', 'STATUS', 'PRIORITY', 'DUE_DATE', 'CONTENT')
    ),
    CONSTRAINT ck_work_item_cell_activity_change CHECK (
        change_type IN ('CREATED', 'ADDED', 'REMOVED', 'CHANGED')
    ),
    CONSTRAINT ck_work_item_cell_activity_values CHECK (
        (before_value IS NULL OR jsonb_typeof(before_value) = 'object')
        AND (after_value IS NULL OR jsonb_typeof(after_value) = 'object')
    ),
    CONSTRAINT ck_work_item_cell_activity_actor CHECK (
        (actor_type IN ('USER', 'ADMIN_OVERRIDE') AND actor_user_id IS NOT NULL)
        OR (actor_type = 'SYSTEM' AND actor_user_id IS NULL
            AND actor_system_code ~ '^[A-Z][A-Z0-9_.:-]{0,79}$')
    ),
    CONSTRAINT ck_work_item_cell_activity_request CHECK (
        request_id ~ '^[A-Za-z0-9._:-]{1,64}$'
        AND correlation_id ~ '^[A-Za-z0-9._:-]{1,64}$'
    )
);

CREATE INDEX idx_work_item_cell_activity_cursor
    ON yumpoo.work_item_cell_activity (
        company_id, work_item_id, occurred_at DESC, id DESC
    );

CREATE INDEX idx_work_item_cell_activity_facets
    ON yumpoo.work_item_cell_activity (
        company_id, work_item_id, actor_user_id, content_id, column_code, occurred_at DESC
    );

COMMENT ON TABLE yumpoo.work_item_cell_activity IS
    'Append-only per-cell work-item activity projection; accurate only from projection state cutover.';

COMMENT ON COLUMN yumpoo.work_item_cell_activity.before_value IS
    'Whitelisted structured display snapshot; never work-item bodies, notes or derived timestamps.';
