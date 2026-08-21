CREATE TABLE yumpoo.admin_override (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    action varchar(64) NOT NULL,
    target_type varchar(32) NOT NULL,
    target_id uuid NOT NULL,
    reason varchar(500) NOT NULL,
    request_hash char(64) NOT NULL,
    idempotency_key uuid NOT NULL,
    actor_user_id uuid NOT NULL,
    before_snapshot jsonb NOT NULL,
    after_snapshot jsonb,
    blocker_counts jsonb NOT NULL,
    result varchar(16) NOT NULL,
    error_code varchar(64),
    occurred_at timestamptz NOT NULL,
    CONSTRAINT fk_admin_override_actor_company
        FOREIGN KEY (actor_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT ck_admin_override_action CHECK (action IN (
        'PROJECT_ARCHIVE_WITH_OPEN_ITEMS',
        'WORKSPACE_ARCHIVE_WITH_ACTIVE_PROJECTS'
    )),
    CONSTRAINT ck_admin_override_target_type CHECK (target_type IN ('PROJECT', 'WORKSPACE')),
    CONSTRAINT ck_admin_override_reason CHECK (
        char_length(reason) BETWEEN 10 AND 500 AND reason = btrim(reason)
    ),
    CONSTRAINT ck_admin_override_result CHECK (result IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_admin_override_result_error CHECK (
        (result = 'SUCCEEDED' AND error_code IS NULL AND after_snapshot IS NOT NULL)
        OR (result = 'FAILED' AND error_code IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_admin_override_idempotency
    ON yumpoo.admin_override (company_id, actor_user_id, action, idempotency_key);

CREATE INDEX idx_admin_override_company_history
    ON yumpoo.admin_override (company_id, occurred_at DESC, id DESC);

CREATE INDEX idx_admin_override_company_target
    ON yumpoo.admin_override (company_id, target_type, target_id, occurred_at DESC);

COMMENT ON TABLE yumpoo.admin_override IS
    'Authorized lifecycle governance overrides with safe snapshots and aggregate blocker counts only.';

ALTER TABLE yumpoo.idempotency_record
    DROP CONSTRAINT ck_idempotency_record_http_status;

ALTER TABLE yumpoo.idempotency_record
    ADD CONSTRAINT ck_idempotency_record_http_status CHECK (
        http_status IS NULL OR http_status BETWEEN 200 AND 499
    );
