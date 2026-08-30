CREATE TABLE yumpoo.activity_projection_state (
    projection_code varchar(32) PRIMARY KEY,
    accepted_from timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT ck_activity_projection_state_code CHECK (
        projection_code IN ('ACTIVITY_V1')
    )
);

INSERT INTO yumpoo.activity_projection_state (projection_code, accepted_from)
VALUES ('ACTIVITY_V1', transaction_timestamp());

CREATE TABLE yumpoo.activity_event (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL,
    projection_code varchar(32) NOT NULL,
    company_id uuid NOT NULL,
    scope_type varchar(16) NOT NULL,
    scope_id uuid NOT NULL,
    entity_type varchar(32) NOT NULL,
    entity_id uuid NOT NULL,
    entity_ref varchar(320),
    event_type varchar(128) NOT NULL,
    actor_type varchar(24) NOT NULL,
    actor_user_id uuid,
    actor_system_code varchar(80),
    actor_display_name varchar(200) NOT NULL,
    occurred_at timestamptz NOT NULL,
    projected_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    template_code varchar(96) NOT NULL,
    safe_parameters jsonb NOT NULL DEFAULT '{}'::jsonb,
    entity_version bigint NOT NULL,
    request_id varchar(64) NOT NULL,
    correlation_id varchar(64) NOT NULL,
    primary_work_item_id uuid,
    secondary_work_item_id uuid,
    CONSTRAINT fk_activity_event_company
        FOREIGN KEY (company_id) REFERENCES yumpoo.company (id),
    CONSTRAINT uq_activity_event_projection
        UNIQUE (event_id, projection_code, scope_type, scope_id),
    CONSTRAINT ck_activity_event_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_activity_event_projection_code CHECK (
        projection_code = 'ACTIVITY_V1'
    ),
    CONSTRAINT ck_activity_event_scope_type CHECK (
        scope_type IN ('PROJECT', 'PRODUCT', 'FEEDBACK')
    ),
    CONSTRAINT ck_activity_event_actor CHECK (
        (actor_type IN ('USER', 'ADMIN_OVERRIDE') AND actor_user_id IS NOT NULL)
        OR (actor_type = 'SYSTEM' AND actor_user_id IS NULL
            AND actor_system_code ~ '^[A-Z][A-Z0-9_.:-]{0,79}$')
    ),
    CONSTRAINT ck_activity_event_safe_parameters CHECK (
        jsonb_typeof(safe_parameters) = 'object'
    ),
    CONSTRAINT ck_activity_event_entity_version CHECK (entity_version >= 0),
    CONSTRAINT ck_activity_event_request CHECK (
        request_id ~ '^[A-Za-z0-9._:-]{1,64}$'
        AND correlation_id ~ '^[A-Za-z0-9._:-]{1,64}$'
    ),
    CONSTRAINT ck_activity_event_related_distinct CHECK (
        secondary_work_item_id IS NULL
        OR primary_work_item_id IS DISTINCT FROM secondary_work_item_id
    )
);

CREATE INDEX idx_activity_event_scope_cursor
    ON yumpoo.activity_event (
        company_id, scope_type, scope_id, occurred_at DESC, id DESC
    );

CREATE INDEX idx_activity_event_primary_work_item_cursor
    ON yumpoo.activity_event (
        company_id, primary_work_item_id, occurred_at DESC, id DESC
    ) WHERE primary_work_item_id IS NOT NULL;

CREATE INDEX idx_activity_event_secondary_work_item_cursor
    ON yumpoo.activity_event (
        company_id, secondary_work_item_id, occurred_at DESC, id DESC
    ) WHERE secondary_work_item_id IS NOT NULL;

COMMENT ON TABLE yumpoo.activity_event IS
    'Append-only Activity projection. Source Outbox rows may be cleaned independently.';

COMMENT ON COLUMN yumpoo.activity_event.safe_parameters IS
    'Whitelisted display facts only; never bodies, reasons, customer fields, hashes or cross-scope identifiers.';
